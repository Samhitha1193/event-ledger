package com.eventledger.gateway.controller;

import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@RestController
@RequestMapping("/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody EventRequest request) {
        String fingerprint = eventService.computeFingerprint(request);

        Optional<Event> existing = eventService.findById(request.eventId());

        if (existing.isPresent()) {
            Event event = existing.get();
            if (event.getPayloadFingerprint().equals(fingerprint)) {
                return ResponseEntity.ok(EventResponse.from(event));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Event ID already exists with a different payload");
        }

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(EventResponse.from(eventService.save(request, fingerprint)));
    }
}
