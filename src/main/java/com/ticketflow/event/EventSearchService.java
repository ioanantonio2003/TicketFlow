package com.ticketflow.event;

import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.event.dto.EventDetailsResponse;
import com.ticketflow.event.dto.PublicEventSummary;
import com.ticketflow.event.dto.SeatMapResponse;
import com.ticketflow.venue.Venue;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public, read-only side of events: only PUBLISHED events are ever visible here. */
@Service
@RequiredArgsConstructor
class EventSearchService {

    private final EventRepository eventRepository;
    private final EventReadRepository readRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    PageResponse<PublicEventSummary> search(String city, EventCategory category, Instant from, Instant to,
                                            BigDecimal maxPrice, String q, Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException("'from' must not be after 'to'.");
        }
        if (maxPrice != null && maxPrice.signum() < 0) {
            throw new BadRequestException("'maxPrice' must not be negative.");
        }
        EventSearchCriteria criteria = new EventSearchCriteria(blankToNull(city), category, from, to, maxPrice,
                blankToNull(q), clock.instant());
        List<Sort.Order> orders = validatedOrders(pageable.getSort());

        // Sorting is part of the specification (minPrice is not a column of events), so the page request
        // itself carries no sort.
        Page<Event> events = eventRepository.findAll(EventSpecifications.search(criteria, orders),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize()));

        // two more queries for the whole page, whatever its size
        List<Long> ids = events.getContent().stream().map(Event::getId).toList();
        Map<Long, BigDecimal> minPrices = ids.isEmpty() ? Map.of() : readRepository.minPrices(ids);
        Map<Long, Long> available = ids.isEmpty() ? Map.of() : readRepository.availableSeats(ids);

        return PageResponse.from(events.map(event -> {
            Venue venue = event.getVenue(); // already loaded by the fetch join
            return new PublicEventSummary(event.getId(), event.getTitle(), event.getCategory(), event.getStartsAt(),
                    venue.getName(), venue.getCity(), minPrices.get(event.getId()),
                    available.getOrDefault(event.getId(), 0L));
        }));
    }

    @Transactional(readOnly = true)
    EventDetailsResponse details(Long eventId) {
        Event event = eventRepository.findWithVenueByIdAndStatus(eventId, EventStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found."));
        Venue venue = event.getVenue();
        return new EventDetailsResponse(event.getId(), event.getTitle(), event.getDescription(),
                event.getCategory(), event.getStartsAt(),
                new EventDetailsResponse.VenueInfo(venue.getName(), venue.getCity(), venue.getAddress()),
                readRepository.sectionAvailability(eventId));
    }

    @Transactional(readOnly = true)
    SeatMapResponse seatMap(Long eventId) {
        if (!eventRepository.existsByIdAndStatus(eventId, EventStatus.PUBLISHED)) {
            throw new ResourceNotFoundException("Event not found.");
        }
        // Rows arrive ordered by section and then seat id (the venue's own order), so grouping them with
        // LinkedHashMaps keeps that order without sorting again.
        Map<Long, SectionBuilder> sections = new LinkedHashMap<>();
        for (EventReadRepository.SeatRow seat : readRepository.seatMap(eventId)) {
            SectionBuilder section = sections.computeIfAbsent(seat.sectionId(),
                    id -> new SectionBuilder(id, seat.sectionName(), seat.price()));
            section.rows.computeIfAbsent(seat.rowLabel(), label -> new ArrayList<>())
                    .add(new SeatMapResponse.Seat(seat.eventSeatId(), seat.seatNumber(), seat.status()));
        }
        return new SeatMapResponse(eventId, sections.values().stream().map(SectionBuilder::build).toList());
    }

    private record SectionBuilder(Long id, String name, BigDecimal price,
                                  Map<String, List<SeatMapResponse.Seat>> rows) {
        SectionBuilder(Long id, String name, BigDecimal price) {
            this(id, name, price, new LinkedHashMap<>());
        }

        SeatMapResponse.Section build() {
            List<SeatMapResponse.Row> rowList = rows.entrySet().stream()
                    .map(row -> new SeatMapResponse.Row(row.getKey(), row.getValue())).toList();
            return new SeatMapResponse.Section(id, name, price, rowList);
        }
    }

    /** Only whitelisted properties may be used for sorting; anything else is a client error. */
    private static List<Sort.Order> validatedOrders(Sort sort) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            if (!EventSpecifications.SORTABLE.contains(order.getProperty())) {
                throw new BadRequestException("Cannot sort by '" + order.getProperty() + "'. Allowed: "
                        + String.join(", ", EventSpecifications.SORTABLE.stream().sorted().toList()));
            }
            orders.add(order);
        }
        if (orders.isEmpty()) {
            orders.add(Sort.Order.asc(EventSpecifications.SORT_STARTS_AT)); // default
        }
        return orders;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
