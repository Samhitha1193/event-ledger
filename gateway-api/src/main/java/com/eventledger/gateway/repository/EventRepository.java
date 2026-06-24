package com.eventledger.gateway.repository;

import com.eventledger.gateway.domain.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
    Optional<Event> findByPayloadFingerprint(String payloadFingerprint);
}
