package com.ticketflow.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Totals per event for the events of one organizer that start in [from, to); a missing bound is open. */
public record OrganizerStatsResponse(Instant from, Instant to, Totals totals, List<EventTotals> events) {

    /** The sum over the listed events. */
    public record Totals(int events, long soldSeats, long paidOrders, BigDecimal revenue) {
    }
}
