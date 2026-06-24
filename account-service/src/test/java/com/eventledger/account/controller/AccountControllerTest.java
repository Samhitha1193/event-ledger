package com.eventledger.account.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountControllerTest {

    @Autowired TestRestTemplate rest;

    @Test
    void getAccount_withTransactions_returnsAccountWithRecentTransactions() {
        post("/accounts/acctctrl-1/transactions", txBody("ac1-e1", "CREDIT", 300, "USD"));
        post("/accounts/acctctrl-1/transactions", txBody("ac1-e2", "DEBIT", 50, "USD"));

        ResponseEntity<Map> resp = rest.getForEntity("/accounts/acctctrl-1", Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("accountId", "acctctrl-1");
        assertThat(resp.getBody()).containsEntry("currency", "USD");
        Number balance = (Number) resp.getBody().get("balance");
        assertThat(balance.doubleValue()).isEqualTo(250.0);
        @SuppressWarnings("unchecked")
        List<?> txns = (List<?>) resp.getBody().get("recentTransactions");
        assertThat(txns).hasSize(2);
    }

    @Test
    void getAccount_unknownAccount_returns404() {
        ResponseEntity<Map> resp = rest.getForEntity("/accounts/acctctrl-unknown", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void health_returnsUpWithDatabaseDetails() {
        ResponseEntity<Map> resp = rest.getForEntity("/health", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("status", "UP");
        assertThat(resp.getBody()).containsKey("components");
    }

    private ResponseEntity<Map> post(String url, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(url, new HttpEntity<>(json, headers), Map.class);
    }

    private String txBody(String eventId, String type, int amount, String currency) {
        return String.format(
                "{\"eventId\":\"%s\",\"type\":\"%s\",\"amount\":%d,\"currency\":\"%s\"," +
                "\"eventTimestamp\":\"2024-01-01T00:00:00Z\"}",
                eventId, type, amount, currency);
    }
}
