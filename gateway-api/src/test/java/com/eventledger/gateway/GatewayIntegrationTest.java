package com.eventledger.gateway;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
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
        // Zero wait between retries so retry tests complete instantly and are deterministic
        registry.add("resilience4j.retry.instances.accountService.wait-duration", () -> "0ms");
        registry.add("resilience4j.retry.instances.accountService.enable-exponential-backoff", () -> "false");
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
    void tracePropagation_incomingTraceparent_sameTraceIdForwardedToAccountService() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        HttpHeaders headers = jsonHeaders();
        headers.set("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01");
        ResponseEntity<Map> resp = rest.postForEntity("/events",
                new HttpEntity<>(event("tr-evt-1", "tr-acct"), headers), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // The same trace ID must propagate gateway → account-service (TraceContextFilter + OTel span inheritance)
        wm.verify(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                .withHeader("traceparent", WireMock.matching("00-" + traceId + "-[0-9a-f]{16}-0[01]")));
    }

    @Test
    void tracePropagation_noTraceparent_gatewayGeneratesAndForwardsToAccountService() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));

        ResponseEntity<Map> resp = submitEvent(event("tr-evt-gen", "tr-acct-gen"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // A well-formed W3C traceparent must be forwarded even when no incoming trace context exists
        wm.verify(postRequestedFor(urlPathMatching("/accounts/.*/transactions"))
                .withHeader("traceparent", WireMock.matching("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")));
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

        // CB is inner (order=2), so it sees each attempt individually.
        // Sub 0: 3 attempts → 3 CB failures. Sub 1: 2 attempts → CB opens on the 5th failure.
        // Sub 1's 3rd attempt and all of subs 2-4 hit an OPEN CB with 0 WireMock calls.
        // 6th call is also CB-rejected. Total WireMock calls: 3 + 2 = 5.
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

    // ── Retry ─────────────────────────────────────────────────────────────────

    @Test
    void retry_transientFailure_eventuallySucceeds_returns201() {
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("transient-failure")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(serverError())
                .willSetStateTo("attempt-2"));
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("transient-failure")
                .whenScenarioStateIs("attempt-2")
                .willReturn(serverError())
                .willSetStateTo("attempt-3"));
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .inScenario("transient-failure")
                .whenScenarioStateIs("attempt-3")
                .willReturn(aResponse().withStatus(201)));

        ResponseEntity<Map> resp = submitEvent(event("retry-evt-1", "retry-acct-1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        wm.verify(exactly(3), postRequestedFor(urlPathMatching("/accounts/.*/transactions")));
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

    // ── Graceful degradation ──────────────────────────────────────────────────

    @Test
    void gracefulDegradation_getEndpointsWorkWhenAccountServiceIsDown() {
        // Store an event while account service is reachable
        wm.stubFor(post(urlPathMatching("/accounts/.*/transactions"))
                .willReturn(aResponse().withStatus(201)));
        submitEvent(event("degrade-evt", "degrade-acct"));

        // Drop all stubs — any unexpected call to WireMock now returns 404
        wm.resetAll();

        // GET /events/{id} must still return 200; it reads only from Gateway's own DB
        ResponseEntity<Map> byId = rest.getForEntity("/events/degrade-evt", Map.class);
        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byId.getBody()).containsEntry("eventId", "degrade-evt");

        // GET /events?account=... must also return 200 for the same reason
        ResponseEntity<Object[]> byAccount = rest.getForEntity(
                "/events?account=degrade-acct", Object[].class);
        assertThat(byAccount.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byAccount.getBody()).hasSize(1);
    }

    // ── Balance fallback ──────────────────────────────────────────────────────

    @Test
    void balanceProxy_accountServiceReturnsError_returns503() {
        wm.stubFor(get(urlPathMatching("/accounts/err-bal-acct/balance"))
                .willReturn(serverError()));

        ResponseEntity<Map> resp = rest.getForEntity("/accounts/err-bal-acct/balance", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<Map> submitEvent(String json) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/events", new HttpEntity<>(json, h), Map.class);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String event(String eventId, String accountId) {
        return String.format(
                "{\"eventId\":\"%s\",\"accountId\":\"%s\",\"type\":\"CREDIT\"," +
                "\"amount\":100,\"currency\":\"USD\",\"eventTimestamp\":\"2026-01-01T00:00:00Z\"}",
                eventId, accountId);
    }
}
