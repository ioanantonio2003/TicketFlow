package com.ticketflow.event;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.event.dto.CreateEventRequest;
import com.ticketflow.event.dto.EventResponse;
import com.ticketflow.event.dto.EventSummary;
import com.ticketflow.event.dto.SetPricesRequest;
import com.ticketflow.event.dto.UpdateEventRequest;
import com.ticketflow.venue.VenueCatalog;
import com.ticketflow.venue.dto.SectionRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Organizer use cases. Every method takes the id of the calling organizer; someone else's event is "not found".
 * Public so that tooling such as the dev data seeder can use the normal rules.
 */
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final PriceCategoryRepository priceCategoryRepository;
    private final EventMapper eventMapper;
    private final VenueCatalog venueCatalog;
    private final Clock clock;

    @Transactional
    public EventResponse create(Long organizerId, CreateEventRequest request) {
        venueCatalog.requireOwned(request.venueId(), organizerId); // 404 if the venue is not mine
        Event event = Event.draft(organizerId, request.venueId(), request.title().trim(),
                trimOrNull(request.description()), request.category(), request.startsAt(), clock.instant());
        eventRepository.save(event);
        return eventMapper.toResponse(event, List.of());
    }

    @Transactional
    EventResponse update(Long organizerId, Long eventId, UpdateEventRequest request) {
        Event event = findOwnedForUpdate(organizerId, eventId);
        event.update(request.title().trim(), trimOrNull(request.description()), request.category(),
                request.startsAt(), clock.instant());
        return toResponse(event);
    }

    /** Replaces the prices of a DRAFT event. Existing rows are updated in place, missing ones are deleted. */
    @Transactional
    public EventResponse setPrices(Long organizerId, Long eventId, SetPricesRequest request) {
        Event event = findOwnedForUpdate(organizerId, eventId);
        event.assertEditable();

        Set<Long> venueSectionIds = venueCatalog.sections(event.getVenueId()).stream()
                .map(SectionRef::id).collect(Collectors.toSet());
        Set<Long> seen = new HashSet<>();
        for (SetPricesRequest.SectionPrice entry : request.prices()) {
            if (!venueSectionIds.contains(entry.sectionId())) {
                throw new BusinessRuleException("Section " + entry.sectionId() + " does not belong to the venue.");
            }
            if (!seen.add(entry.sectionId())) {
                throw new BusinessRuleException("Section " + entry.sectionId() + " has more than one price.");
            }
        }

        Map<Long, PriceCategory> existing = priceCategoryRepository.findByEventIdOrderBySectionId(eventId).stream()
                .collect(Collectors.toMap(PriceCategory::getSectionId, Function.identity()));
        for (SetPricesRequest.SectionPrice entry : request.prices()) {
            PriceCategory current = existing.remove(entry.sectionId());
            if (current != null) {
                current.changePrice(entry.price());
            } else {
                priceCategoryRepository.save(new PriceCategory(event, entry.sectionId(), entry.price()));
            }
        }
        priceCategoryRepository.deleteAll(existing.values()); // sections no longer in the request
        priceCategoryRepository.flush();
        return toResponse(event);
    }

    /**
     * Publishing turns the venue's seats into event seats with one INSERT ... SELECT (not one entity per
     * seat). The event row is locked, so concurrent price edits or a second publish wait and then fail
     * the DRAFT check.
     */
    @Transactional
    public EventResponse publish(Long organizerId, Long eventId) {
        Event event = findOwnedForUpdate(organizerId, eventId);
        event.publish(clock.instant());

        List<PriceCategory> prices = priceCategoryRepository.findByEventIdOrderBySectionId(eventId);
        assertEverySectionHasAPrice(event, prices);

        int created = eventRepository.generateEventSeats(eventId, event.getVenueId());
        long expected = venueCatalog.seatCount(event.getVenueId());
        if (created != expected) {
            // should be impossible; rolling back is safer than publishing an incomplete seat map
            throw new IllegalStateException("Created " + created + " event seats, expected " + expected);
        }
        return eventMapper.toResponse(event, prices);
    }

    @Transactional(readOnly = true)
    PageResponse<EventSummary> listMine(Long organizerId, Pageable pageable) {
        return PageResponse.from(eventRepository.findByOrganizerId(organizerId, pageable)
                .map(eventMapper::toSummary));
    }

    private void assertEverySectionHasAPrice(Event event, List<PriceCategory> prices) {
        Set<Long> priced = prices.stream().map(PriceCategory::getSectionId).collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        for (SectionRef section : venueCatalog.sections(event.getVenueId())) {
            if (!priced.contains(section.id())) {
                missing.add(section.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessRuleException("Set a price for every section before publishing. Missing: "
                    + String.join(", ", missing));
        }
    }

    private Event findOwnedForUpdate(Long organizerId, Long eventId) {
        return eventRepository.findByIdAndOrganizerIdForUpdate(eventId, organizerId)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found."));
    }

    private EventResponse toResponse(Event event) {
        return eventMapper.toResponse(event, priceCategoryRepository.findByEventIdOrderBySectionId(event.getId()));
    }

    private static String trimOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
