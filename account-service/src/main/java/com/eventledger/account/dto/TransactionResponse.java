package com.eventledger.account.dto;

import com.eventledger.account.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        String eventId,
        Long accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp
) {}
