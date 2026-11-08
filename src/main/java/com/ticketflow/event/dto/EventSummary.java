package com.ticketflow.event.dto;

import com.ticketflow.event.EventCategory;
import com.ticketflow.event.EventStatus;
import java.time.Instant;

public record EventSummary(Long id, Long venueId, String title, EventCategory category, Instant startsAt,
                           EventStatus status) {
}
