package com.eventledger.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "event",
    uniqueConstraints = @UniqueConstraint(name = "uk_event_fingerprint", columnNames = "payload_fingerprint")
)
public class Event {

    @Id
    @Column(name = "event_id", updatable = false, nullable = false)
    private String eventId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private EventType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "event_timestamp", nullable = false, updatable = false)
    private Instant eventTimestamp;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "payload_fingerprint", nullable = false, updatable = false, length = 64)
    private String payloadFingerprint;

    protected Event() {}

    public Event(String eventId, String accountId, EventType type, BigDecimal amount,
                 String currency, Instant eventTimestamp, String metadata, String payloadFingerprint) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadata = metadata;
        this.payloadFingerprint = payloadFingerprint;
    }

    public String getEventId()            { return eventId; }
    public String getAccountId()          { return accountId; }
    public EventType getType()            { return type; }
    public BigDecimal getAmount()         { return amount; }
    public String getCurrency()           { return currency; }
    public Instant getEventTimestamp()    { return eventTimestamp; }
    public String getMetadata()           { return metadata; }
    public String getPayloadFingerprint() { return payloadFingerprint; }
}
