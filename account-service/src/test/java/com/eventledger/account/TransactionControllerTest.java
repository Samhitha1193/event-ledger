package com.eventledger.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransactionControllerTest {

    @Autowired TestRestTemplate rest;

    @Test
    void postTransaction_newAccount_autoCreatesAndReturns201() {
        ResponseEntity<Map> resp = post("/accounts/ac1/transactions",
                txBody("tx-ac1-e1", "CREDIT", 200, "USD"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody()).containsEntry("eventId", "tx-ac1-e1");
        assertThat(resp.getBody()).containsEntry("accountId", "ac1");
    }

    @Test
    void postTransaction_sameEventId_returns200Idempotent() {
        post("/accounts/ac2/transactions", txBody("tx-ac2-e1", "CREDIT", 100, "USD"));
        ResponseEntity<Map> resp = post("/accounts/ac2/transactions",
                txBody("tx-ac2-e1", "CREDIT", 100, "USD"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getBalance_afterCreditsAndDebits_returnsCorrectBalance() {
        post("/accounts/ac3/transactions", txBody("tx-ac3-e1", "CREDIT", 500, "EUR"));
        post("/accounts/ac3/transactions", txBody("tx-ac3-e2", "DEBIT", 150, "EUR"));

        ResponseEntity<Map> resp = rest.getForEntity("/accounts/ac3/balance", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("accountId", "ac3");
        assertThat(resp.getBody()).containsEntry("currency", "EUR");
        Number balance = (Number) resp.getBody().get("balance");
        assertThat(balance.doubleValue()).isEqualTo(350.0);
    }

    @Test
    void getBalance_unknownAccount_returns404() {
        ResponseEntity<Map> resp = rest.getForEntity("/accounts/no-such-acct/balance", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void postTransaction_currencyMismatch_returns422() {
        post("/accounts/ac4/transactions", txBody("tx-ac4-e1", "CREDIT", 100, "USD"));
        ResponseEntity<Map> resp = post("/accounts/ac4/transactions",
                txBody("tx-ac4-e2", "CREDIT", 50, "EUR"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map> post(String url, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url, new HttpEntity<>(json, headers), Map.class);
    }

    private String txBody(String eventId, String type, int amount, String currency) {
        return String.format(
                "{\"eventId\":\"%s\",\"type\":\"%s\",\"amount\":%d,\"currency\":\"%s\",\"eventTimestamp\":\"2024-01-01T00:00:00Z\"}",
                eventId, type, amount, currency);
    }
}
