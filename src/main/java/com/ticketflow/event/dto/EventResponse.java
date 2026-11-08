package com.ticketflow.event.dto;

import com.ticketflow.event.EventCategory;
import com.ticketflow.event.EventStatus;
import java.time.Instant;
import java.util.List;

public record EventResponse(Long id, Long venueId, String title, String description, EventCategory category,
                            Instant startsAt, EventStatus status, List<PriceResponse> prices) {
}
