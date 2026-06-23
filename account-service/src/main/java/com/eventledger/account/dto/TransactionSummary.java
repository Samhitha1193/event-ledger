package com.eventledger.account.dto;

import com.eventledger.account.domain.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionSummary(
        UUID eventId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp
) {}
