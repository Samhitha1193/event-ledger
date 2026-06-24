package com.eventledger.gateway.controller;

import com.eventledger.gateway.domain.Event;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.service.EventService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
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

    @GetMapping("/{id}")
    public ResponseEntity<EventResponse> getById(@PathVariable String id) {
        return eventService.findById(id)
                .map(EventResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Event not found: " + id));
    }

    @GetMapping
    public ResponseEntity<List<EventResponse>> listByAccount(@RequestParam("account") String accountId) {
        List<EventResponse> events = eventService.findByAccountId(accountId).stream()
                .map(EventResponse::from)
                .toList();
        return ResponseEntity.ok(events);
    }
}
