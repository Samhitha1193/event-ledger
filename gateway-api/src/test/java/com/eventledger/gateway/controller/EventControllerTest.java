package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.BalanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventControllerTest {

    @Autowired TestRestTemplate rest;
    @MockBean AccountServiceClient accountServiceClient;

    @BeforeEach
    void setUp() {
        when(accountServiceClient.applyTransaction(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }


    @Test
    void postEvent_newEvent_returns201() {
        ResponseEntity<Map> resp = post("/events", eventBody("gw-evt-1", "acct-a", "2024-01-01T00:00:00Z"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsEntry("eventId", "gw-evt-1");
        assertThat(resp.getBody()).containsKey("payloadFingerprint");
    }

    @Test
    void postEvent_identicalPayload_returns200() {
        post("/events", eventBody("gw-evt-2", "acct-b", "2024-01-01T00:00:00Z"));
        ResponseEntity<Map> resp = post("/events", eventBody("gw-evt-2", "acct-b", "2024-01-01T00:00:00Z"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void postEvent_sameIdDifferentPayload_returns409() {
        post("/events", eventBody("gw-evt-3", "acct-c", "2024-01-01T00:00:00Z"));
        ResponseEntity<Map> resp = post("/events",
                "{\"eventId\":\"gw-evt-3\",\"accountId\":\"acct-c\",\"type\":\"DEBIT\"," +
                "\"amount\":999,\"currency\":\"USD\",\"eventTimestamp\":\"2024-06-01T00:00:00Z\"}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getEvent_exists_returns200() {
        post("/events", eventBody("gw-evt-4", "acct-d", "2024-01-01T00:00:00Z"));
        ResponseEntity<Map> resp = rest.getForEntity("/events/gw-evt-4", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("accountId", "acct-d");
    }

    @Test
    void getEvent_notFound_returns404() {
        ResponseEntity<Map> resp = rest.getForEntity("/events/no-such-id", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void listEvents_byAccount_returnsSortedByTimestamp() {
        post("/events", eventBody("gw-evt-list-2", "acct-e", "2024-02-01T00:00:00Z"));
        post("/events", eventBody("gw-evt-list-1", "acct-e", "2024-01-01T00:00:00Z"));

        ResponseEntity<Object[]> resp = rest.getForEntity("/events?account=acct-e", Object[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(((Map<?, ?>) resp.getBody()[0]).get("eventId")).isEqualTo("gw-evt-list-1");
        assertThat(((Map<?, ?>) resp.getBody()[1]).get("eventId")).isEqualTo("gw-evt-list-2");
    }

    @Test
    void postEvent_missingRequiredFields_returns400WithFieldErrors() {
        ResponseEntity<Map> resp = post("/events", "{\"type\":\"CREDIT\",\"amount\":100}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> errors = (Map<String, Object>) resp.getBody().get("errors");
        assertThat(errors).containsKey("eventId");
        assertThat(errors).containsKey("accountId");
    }

    @Test
    void postEvent_negativeAmount_returns400() {
        ResponseEntity<Map> resp = post("/events",
                "{\"eventId\":\"gw-neg\",\"accountId\":\"acct-n\",\"type\":\"CREDIT\"," +
                "\"amount\":-5,\"currency\":\"USD\",\"eventTimestamp\":\"2024-01-01T00:00:00Z\"}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void postEvent_accountServiceUnavailable_returns503() {
        when(accountServiceClient.applyTransaction(any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Account Service unavailable")));

        ResponseEntity<Map> resp = post("/events", eventBody("gw-fail-1", "acct-f", "2024-01-01T00:00:00Z"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void outOfOrder_threeEventsSubmittedOutOfChronologicalOrder_listReturnsSortedByTimestamp() {
        post("/events", eventBody("oo-evt-c", "acct-oo", "2024-03-01T00:00:00Z"));
        post("/events", eventBody("oo-evt-a", "acct-oo", "2024-01-01T00:00:00Z"));
        post("/events", eventBody("oo-evt-b", "acct-oo", "2024-02-01T00:00:00Z"));

        ResponseEntity<Object[]> resp = rest.getForEntity("/events?account=acct-oo", Object[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).hasSize(3);
        assertThat(((Map<?, ?>) resp.getBody()[0]).get("eventId")).isEqualTo("oo-evt-a");
        assertThat(((Map<?, ?>) resp.getBody()[1]).get("eventId")).isEqualTo("oo-evt-b");
        assertThat(((Map<?, ?>) resp.getBody()[2]).get("eventId")).isEqualTo("oo-evt-c");
    }

    @Test
    void postEvent_zeroAmount_returns400() {
        ResponseEntity<Map> resp = post("/events",
                "{\"eventId\":\"gw-zero\",\"accountId\":\"acct-z\",\"type\":\"CREDIT\"," +
                "\"amount\":0,\"currency\":\"USD\",\"eventTimestamp\":\"2024-01-01T00:00:00Z\"}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getBalance_accountServiceReturnsBalance_proxiesResponse() {
        when(accountServiceClient.getBalance(eq("acct-h")))
                .thenReturn(CompletableFuture.completedFuture(
                        new BalanceResponse("acct-h", "USD", BigDecimal.valueOf(750))));

        ResponseEntity<Map> resp = rest.getForEntity("/accounts/acct-h/balance", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("accountId", "acct-h");
        Number balance = (Number) resp.getBody().get("balance");
        assertThat(balance.doubleValue()).isEqualTo(750.0);
    }

    @Test
    void getBalance_accountServiceDown_returns503() {
        when(accountServiceClient.getBalance(any()))
                .thenReturn(CompletableFuture.failedFuture(
                        new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                                "Account Service unavailable")));

        ResponseEntity<Map> resp = rest.getForEntity("/accounts/acct-x/balance", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void postEvent_metadataObject_preservedInResponse() {
        String body = "{\"eventId\":\"gw-meta-1\",\"accountId\":\"acct-g\",\"type\":\"CREDIT\"," +
                "\"amount\":100,\"currency\":\"USD\",\"eventTimestamp\":\"2024-01-01T00:00:00Z\"," +
                "\"metadata\":{\"source\":\"batch\",\"batchId\":\"B-001\"}}";
        ResponseEntity<Map> resp = post("/events", body);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().get("metadata")).isInstanceOf(Map.class);
    }

    @Test
    void postEvent_malformedJson_returns400() {
        ResponseEntity<Map> resp = post("/events", "{this is not valid json}");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map> post(String url, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url, new HttpEntity<>(json, headers), Map.class);
    }

    private String eventBody(String eventId, String accountId, String ts) {
        return String.format(
                "{\"eventId\":\"%s\",\"accountId\":\"%s\",\"type\":\"CREDIT\"," +
                "\"amount\":100,\"currency\":\"USD\",\"eventTimestamp\":\"%s\"}",
                eventId, accountId, ts);
    }
}
