package com.ticketflow.event;

import com.ticketflow.venue.VenueUsageCheck;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Tells the venue feature that a venue with events must not be deleted. */
@Component
@RequiredArgsConstructor
class EventVenueUsageCheck implements VenueUsageCheck {

    private final EventRepository eventRepository;

    @Override
    public boolean isVenueUsed(Long venueId) {
        return eventRepository.existsByVenueId(venueId);
    }
}
