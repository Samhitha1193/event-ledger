package com.eventledger.account.controller;

import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/{id}/transactions")
    public ResponseEntity<Void> createTransaction(
            @PathVariable Long id,
            @Valid @RequestBody TransactionRequest request) {
        boolean isNew = transactionService.process(id, request);
        return isNew ? ResponseEntity.status(201).build() : ResponseEntity.ok().build();
    }
}
