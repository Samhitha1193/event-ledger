package com.eventledger.gateway;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration tests: real gateway HTTP server → real AccountServiceClient → WireMock.
 * No mocks in the Spring context — the account-service is replaced by WireMock on a dynamic port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIntegrationTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("account.service.url", wm::baseUrl);
        // Shrink the circuit-breaker window so the test only needs 5 calls to open it
        registry.add("resilience4j.circuitbreaker.instances.accountService.sliding-window-size", () -> "5");
        registry.add("resilience4j.circuitbreaker.instances.accountService.minimum-number-of-calls", () -> "5");
        registry.add("resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state", () -> "2s");
        // Shorten the TimeLimiter so the timeout test completes in ~1 s
        registry.add("resilience4j.timelimiter.instances.accountService.timeout-duration", () -> "1s");
    }

    @Autowired TestRestTemplate rest;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        wm.resetAll();
    }

    // ── Integration ───────────────────────────────────────────────────────────

    @Test
    void fullFlow_postEvent_forwardsToAccountService_returns201() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        ResponseEntity<Map> resp = submitEvent(event("int-evt-1", "int-acct-1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsEntry("eventId", "int-evt-1");
        assertThat(resp.getBody()).containsKey("payloadFingerprint");
        wm.verify(1, postRequestedFor(urlPathMatching("/accounts/int-acct-1/transactions")));
    }

    @Test
    void idempotency_duplicateSubmit_accountServiceCalledOnlyOnce() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        submitEvent(event("int-evt-dup", "int-acct-d"));
        ResponseEntity<Map> dup = submitEvent(event("int-evt-dup", "int-acct-d"));

        assertThat(dup.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Gateway short-circuits after the DB hit; account-service sees exactly one real call
        wm.verify(exactly(1), postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    // ── Trace propagation ─────────────────────────────────────────────────────

    @Test
    void tracePropagation_customTraceId_echoedAndForwardedToAccountService() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Trace-Id", "trace-abc-123");
        ResponseEntity<Map> resp = rest.postForEntity("/events",
                new HttpEntity<>(event("tr-evt-1", "tr-acct"), headers), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Gateway echoes the same trace ID in the response
        assertThat(resp.getHeaders().getFirst("X-Trace-Id")).isEqualTo("trace-abc-123");
        // The real HTTP call to account-service carried the trace ID header
        wm.verify(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                .withHeader("X-Trace-Id", WireMock.equalTo("trace-abc-123")));
    }

    @Test
    void tracePropagation_noTraceId_gatewayGeneratesAndForwardsToAccountService() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        ResponseEntity<Map> resp = submitEvent(event("tr-evt-gen", "tr-acct-gen"));

        String generated = resp.getHeaders().getFirst("X-Trace-Id");
        assertThat(generated).isNotBlank();
        // The generated ID is forwarded to the account-service without modification
        wm.verify(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                .withHeader("X-Trace-Id", WireMock.equalTo(generated)));
    }

    // ── Resiliency ────────────────────────────────────────────────────────────

    @Test
    void resiliency_accountServiceReturns500_gatewayReturns503() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(serverError()));

        ResponseEntity<Map> resp = submitEvent(event("res-evt-1", "res-acct-1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void circuitBreaker_opensAfterFiveFailures_sixthCallIsRejectedImmediately() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(serverError()));

        // Fill the sliding window (size=5) with failures
        for (int i = 0; i < 5; i++) {
            submitEvent(event("cb-evt-" + i, "cb-acct"));
        }

        assertThat(circuitBreakerRegistry.circuitBreaker("accountService").getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // The next call is rejected before it reaches WireMock
        ResponseEntity<Map> openResp = submitEvent(event("cb-evt-rejected", "cb-acct"));
        assertThat(openResp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat((String) openResp.getBody().get("message"))
                .containsIgnoringCase("circuit breaker");

        // Exactly 5 requests reached WireMock — the 6th was short-circuited
        wm.verify(exactly(5), postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
    }

    @Test
    void timelimiter_slowAccountService_returns503BeforeDelayCompletes() {
        // 2 s delay > 1 s TimeLimiter configured above
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(2000)));

        ResponseEntity<Map> resp = submitEvent(event("tm-evt-1", "tm-acct"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── Balance proxy ─────────────────────────────────────────────────────────

    @Test
    void balanceProxy_proxiesToAccountService_returnsBalance() {
        wm.stubFor(get(urlPathMatching("/accounts/bal-acct/balance"))
                .willReturn(okJson(
                        "{\"accountId\":\"bal-acct\",\"currency\":\"USD\",\"balance\":450.00}")));

        ResponseEntity<Map> resp = rest.getForEntity("/accounts/bal-acct/balance", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("accountId", "bal-acct");
        Number balance = (Number) resp.getBody().get("balance");
        assertThat(balance.doubleValue()).isEqualTo(450.0);
        wm.verify(1, getRequestedFor(urlPathMatching("/accounts/bal-acct/balance")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map> submitEvent(String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/events", new HttpEntity<>(json, h), Map.class);
    }

    private String event(String eventId, String accountId) {
        return String.format(
                "{\"eventId\":\"%s\",\"accountId\":\"%s\",\"type\":\"CREDIT\"," +
                "\"amount\":100,\"currency\":\"USD\",\"eventTimestamp\":\"2026-01-01T00:00:00Z\"}",
                eventId, accountId);
    }
}
