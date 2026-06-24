package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.domain.EventType;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.repository.EventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    @Mock EventRepository repository;
    @Mock AccountServiceClient accountServiceClient;
    @Mock ObjectMapper objectMapper;

    EventService eventService;

    @BeforeEach
    void setup() {
        eventService = new EventService(repository, accountServiceClient,
                new SimpleMeterRegistry(), objectMapper);
    }

    // ── computeFingerprint ────────────────────────────────────────────────────

    @Test
    void computeFingerprint_sameInput_returnsConsistentSha256Hex() {
        EventRequest req = new EventRequest(
                "evt-1", "acct-1", EventType.CREDIT,
                BigDecimal.ONE, "USD", Instant.parse("2024-01-01T00:00:00Z"), null);

        String fp1 = eventService.computeFingerprint(req);
        String fp2 = eventService.computeFingerprint(req);

        assertThat(fp1).isEqualTo(fp2);
        assertThat(fp1).matches("[0-9a-f]{64}");
    }

    @Test
    void computeFingerprint_differentAmount_returnsDifferentHash() {
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        EventRequest req1 = new EventRequest("e", "a", EventType.CREDIT, BigDecimal.ONE, "USD", ts, null);
        EventRequest req2 = new EventRequest("e", "a", EventType.CREDIT, BigDecimal.TEN, "USD", ts, null);

        assertThat(eventService.computeFingerprint(req1))
                .isNotEqualTo(eventService.computeFingerprint(req2));
    }

    @Test
    void computeFingerprint_sha256Unavailable_throwsIllegalState() {
        try (MockedStatic<MessageDigest> mocked = mockStatic(MessageDigest.class)) {
            mocked.when(() -> MessageDigest.getInstance(anyString()))
                  .thenThrow(new NoSuchAlgorithmException("algorithm unavailable"));

            EventRequest req = new EventRequest(
                    "fp-evt", "fp-acct", EventType.CREDIT,
                    BigDecimal.ONE, "USD", Instant.now(), null);

            assertThrows(IllegalStateException.class,
                    () -> eventService.computeFingerprint(req));
        }
    }

    // ── save ─────────────────────────────────────────────────────────────────

    @Test
    void save_successfulSave_returnsPersistedEvent() {
        when(accountServiceClient.applyTransaction(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Event persisted = new Event("evt-save", "acct-save", EventType.CREDIT,
                BigDecimal.ONE, "USD", Instant.now(), null, "fp");
        when(repository.save(any())).thenReturn(persisted);

        EventRequest req = new EventRequest(
                "evt-save", "acct-save", EventType.CREDIT,
                BigDecimal.ONE, "USD", Instant.now(), null);

        Event result = eventService.save(req, "fp");

        assertThat(result).isSameAs(persisted);
    }

    @Test
    void save_metadataSerializationFails_throwsIllegalArgument() throws Exception {
        when(accountServiceClient.applyTransaction(any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("serialization failed") {});

        EventRequest req = new EventRequest(
                "ser-evt", "ser-acct", EventType.CREDIT,
                BigDecimal.ONE, "USD", Instant.now(), Map.of("key", "value"));

        assertThrows(IllegalArgumentException.class,
                () -> eventService.save(req, "fingerprint"));
    }

    // ── findById ─────────────────────────────────────────────────────────────

    @Test
    void findById_existingId_returnsEvent() {
        Event event = new Event("evt-find", "acct-1", EventType.CREDIT,
                BigDecimal.ONE, "USD", Instant.now(), null, "fp");
        when(repository.findById("evt-find")).thenReturn(Optional.of(event));

        Optional<Event> result = eventService.findById("evt-find");

        assertThat(result).contains(event);
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        when(repository.findById("unknown")).thenReturn(Optional.empty());

        assertThat(eventService.findById("unknown")).isEmpty();
    }

    // ── findByAccountId ───────────────────────────────────────────────────────

    @Test
    void findByAccountId_returnsEventsInOrderFromRepository() {
        Event e1 = new Event("e1", "acct-list", EventType.CREDIT,
                BigDecimal.ONE, "USD", Instant.parse("2024-01-01T00:00:00Z"), null, "fp1");
        Event e2 = new Event("e2", "acct-list", EventType.DEBIT,
                BigDecimal.TEN, "USD", Instant.parse("2024-02-01T00:00:00Z"), null, "fp2");
        when(repository.findByAccountIdOrderByEventTimestampAscEventIdAsc("acct-list"))
                .thenReturn(List.of(e1, e2));

        List<Event> results = eventService.findByAccountId("acct-list");

        assertThat(results).containsExactly(e1, e2);
    }

    @Test
    void findByAccountId_noEvents_returnsEmptyList() {
        when(repository.findByAccountIdOrderByEventTimestampAscEventIdAsc("empty-acct"))
                .thenReturn(List.of());

        assertThat(eventService.findByAccountId("empty-acct")).isEmpty();
    }
}
