package com.ticketflow.reporting;

import com.ticketflow.common.web.CurrentUser;
import com.ticketflow.reporting.dto.EventStatsResponse;
import com.ticketflow.reporting.dto.OrganizerStatsResponse;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sales figures for organizers; /api/organizer/** is restricted to ORGANIZER and ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/organizer")
@RequiredArgsConstructor
class ReportingController {

    private final ReportingService reportingService;

    /** Seats by state, occupancy, revenue, orders by status and sales per section of one of my events. */
    @GetMapping("/events/{id}/stats")
    EventStatsResponse eventStats(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return reportingService.eventStats(CurrentUser.id(jwt), id);
    }

    /** Totals per event for my events starting in [from, to) (ISO-8601 instants, both optional). */
    @GetMapping("/stats")
    OrganizerStatsResponse organizerStats(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(required = false) Instant from,
                                          @RequestParam(required = false) Instant to) {
        return reportingService.organizerStats(CurrentUser.id(jwt), from, to);
    }
}
