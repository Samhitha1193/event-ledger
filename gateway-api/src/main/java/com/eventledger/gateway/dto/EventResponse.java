package com.eventledger.gateway.dto;

import com.eventledger.gateway.domain.Event;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.math.BigDecimal;
import java.time.Instant;

public record EventResponse(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        @JsonRawValue String metadata,
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
