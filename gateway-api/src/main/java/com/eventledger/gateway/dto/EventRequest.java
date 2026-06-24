package com.eventledger.gateway.dto;

import com.eventledger.gateway.domain.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EventRequest(
        @NotNull(message = "eventId is required")
        UUID eventId,

        @NotNull(message = "accountId is required")
        UUID accountId,

        @NotNull(message = "type is required")
        EventType type,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be above zero")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        String currency,

        @NotNull(message = "eventTimestamp is required")
        Instant eventTimestamp,

        String metadata
) {}
