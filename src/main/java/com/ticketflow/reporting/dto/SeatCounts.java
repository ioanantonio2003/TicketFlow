package com.ticketflow.reporting.dto;

/** Seats of an event (or of one section) by state. Held seats whose hold ran out but was not swept yet count as held. */
public record SeatCounts(long total, long available, long held, long sold) {
}
