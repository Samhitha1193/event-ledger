package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class EventService {

    private final EventRepository repository;
    private final AccountServiceClient accountServiceClient;

    public EventService(EventRepository repository, AccountServiceClient accountServiceClient) {
        this.repository = repository;
        this.accountServiceClient = accountServiceClient;
    }

    public String computeFingerprint(EventRequest req) {
        String raw = String.join("|",
                req.eventId().toString(),
                req.accountId().toString(),
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
        accountServiceClient.applyTransaction(req);
        return repository.save(new Event(
                req.eventId(),
                req.accountId(),
                req.type(),
                req.amount(),
                req.currency(),
                req.eventTimestamp(),
                req.metadata(),
                fingerprint
        ));
    }

    public Optional<Event> findById(java.util.UUID id) {
        return repository.findById(id);
    }
}
