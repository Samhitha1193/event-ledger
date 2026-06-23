package com.eventledger.account.controller;

import com.eventledger.account.domain.Transaction;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.dto.TransactionResponse;
import com.eventledger.account.service.TransactionService;
import com.eventledger.account.service.TransactionService.ProcessResult;
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
    public ResponseEntity<TransactionResponse> createTransaction(
            @PathVariable Long id,
            @Valid @RequestBody TransactionRequest request) {
        ProcessResult result = transactionService.process(id, request);
        TransactionResponse body = toResponse(result.transaction(), result.accountId());
        return result.isNew()
                ? ResponseEntity.status(201).body(body)
                : ResponseEntity.ok(body);
    }

    private TransactionResponse toResponse(Transaction tx, Long accountId) {
        return new TransactionResponse(
                tx.getEventId(),
                accountId,
                tx.getType(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getEventTimestamp()
        );
    }
}
