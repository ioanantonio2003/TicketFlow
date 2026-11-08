package com.ticketflow.event.dto;

import com.ticketflow.event.EventCategory;
import java.time.Instant;
import java.util.List;

public record EventDetailsResponse(Long id, String title, String description, EventCategory category,
                                   Instant startsAt, VenueInfo venue, List<SectionAvailability> sections) {

    public record VenueInfo(String name, String city, String address) {
    }

    /** Price and availability of one section for this event. */
    public record SectionAvailability(Long sectionId, String name, java.math.BigDecimal price, long availableSeats,
                                      long totalSeats) {
    }
}
