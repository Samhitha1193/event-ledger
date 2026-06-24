package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.dto.BalanceResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/accounts")
public class BalanceController {

    private final AccountServiceClient accountServiceClient;

    public BalanceController(AccountServiceClient accountServiceClient) {
        this.accountServiceClient = accountServiceClient;
    }

    @GetMapping("/{id}/balance")
    public CompletableFuture<ResponseEntity<BalanceResponse>> getBalance(@PathVariable String id) {
        return accountServiceClient.getBalance(id).thenApply(ResponseEntity::ok);
    }
}
