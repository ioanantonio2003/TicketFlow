package com.ticketflow.event;

import com.ticketflow.common.web.PageResponse;
import com.ticketflow.event.dto.EventDetailsResponse;
import com.ticketflow.event.dto.PublicEventSummary;
import com.ticketflow.event.dto.SeatMapResponse;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public catalogue (no login): GET requests under /api/events are permitted in SecurityConfig. */
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
class PublicEventController {

    private final EventSearchService searchService;

    /**
     * Sort with {@code sort=startsAt|title|minPrice[,desc]}; default is {@code startsAt} ascending.
     * Dates are ISO-8601 instants, e.g. {@code from=2031-05-01T00:00:00Z}.
     */
    @GetMapping
    PageResponse<PublicEventSummary> search(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) EventCategory category,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return searchService.search(city, category, from, to, maxPrice, q, pageable);
    }

    @GetMapping("/{id}")
    EventDetailsResponse details(@PathVariable Long id) {
        return searchService.details(id);
    }

    @GetMapping("/{id}/seats")
    SeatMapResponse seats(@PathVariable Long id) {
        return searchService.seatMap(id);
    }
}
