package com.eventledger.gateway.dto;

import com.eventledger.gateway.domain.Event;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EventResponse(
        UUID eventId,
        UUID accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        String metadata,
        String payloadFingerprint
) {
    public static EventResponse from(Event event) {
        return new EventResponse(
                event.getEventId(),
                event.getAccountId(),
                event.getType().name(),
                event.getAmount(),
                event.getCurrency(),
                event.getEventTimestamp(),
                event.getMetadata(),
                event.getPayloadFingerprint()
        );
    }
}
