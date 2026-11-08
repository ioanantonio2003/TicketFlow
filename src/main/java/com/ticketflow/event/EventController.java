package com.ticketflow.event;

import com.ticketflow.common.web.CurrentUser;
import com.ticketflow.event.dto.CreateEventRequest;
import com.ticketflow.event.dto.EventResponse;
import com.ticketflow.event.dto.SetPricesRequest;
import com.ticketflow.event.dto.UpdateEventRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Organizer operations. POST/PUT under /api/events are restricted to ORGANIZER and ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
class EventController {

    private final EventService eventService;

    @PostMapping
    ResponseEntity<EventResponse> create(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreateEventRequest request) {
        EventResponse event = eventService.create(CurrentUser.id(jwt), request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(event.id()).toUri()).body(event);
    }

    @PutMapping("/{id}")
    EventResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                         @Valid @RequestBody UpdateEventRequest request) {
        return eventService.update(CurrentUser.id(jwt), id, request);
    }

    @PutMapping("/{id}/prices")
    EventResponse setPrices(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                            @Valid @RequestBody SetPricesRequest request) {
        return eventService.setPrices(CurrentUser.id(jwt), id, request);
    }

    @PostMapping("/{id}/publish")
    EventResponse publish(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return eventService.publish(CurrentUser.id(jwt), id);
    }
}
