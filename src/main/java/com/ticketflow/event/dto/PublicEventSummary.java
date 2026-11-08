package com.ticketflow.event.dto;

import com.ticketflow.event.EventCategory;
import java.math.BigDecimal;
import java.time.Instant;

/** One row of the public event list. {@code minPrice} is the cheapest section price. */
public record PublicEventSummary(Long id, String title, EventCategory category, Instant startsAt, String venueName,
                                 String city, BigDecimal minPrice, long availableSeats) {
}
