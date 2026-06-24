package com.eventledger.gateway.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountTransactionRequest(
        String eventId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp
) {}
