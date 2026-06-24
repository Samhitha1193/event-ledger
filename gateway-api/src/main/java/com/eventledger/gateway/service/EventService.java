package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository repository;
    private final AccountServiceClient accountServiceClient;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    public EventService(EventRepository repository,
                        AccountServiceClient accountServiceClient,
                        MeterRegistry meterRegistry,
                        ObjectMapper objectMapper) {
        this.repository           = repository;
        this.accountServiceClient = accountServiceClient;
        this.meterRegistry        = meterRegistry;
        this.objectMapper         = objectMapper;
    }

    public String computeFingerprint(EventRequest req) {
        String raw = String.join("|",
                req.eventId(),
                req.accountId(),
                req.type().name(),
                req.amount().stripTrailingZeros().toPlainString(),
                req.currency(),
                req.eventTimestamp().toString()
        );
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public Event save(EventRequest req, String fingerprint) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            AccountServiceClient.block(accountServiceClient.applyTransaction(req));
        } finally {
            sample.stop(meterRegistry.timer("account.service.call.duration",
                    "outcome", "completed"));
        }

        String metadataJson = null;
        if (req.metadata() != null) {
            try {
                metadataJson = objectMapper.writeValueAsString(req.metadata());
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Invalid metadata", e);
            }
        }

        Event saved = repository.save(new Event(
                req.eventId(),
                req.accountId(),
                req.type(),
                req.amount(),
                req.currency(),
                req.eventTimestamp(),
                metadataJson,
                fingerprint
        ));

        meterRegistry.counter("events.submitted", "type", req.type().name()).increment();
        log.info("Event accepted eventId={} accountId={} type={} amount={} currency={}",
                req.eventId(), req.accountId(), req.type(), req.amount(), req.currency());
        return saved;
    }

    public Optional<Event> findById(String id) {
        return repository.findById(id);
    }

    public List<Event> findByAccountId(String accountId) {
        return repository.findByAccountIdOrderByEventTimestampAscEventIdAsc(accountId);
    }
}
