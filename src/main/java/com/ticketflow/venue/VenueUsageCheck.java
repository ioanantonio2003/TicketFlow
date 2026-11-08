package com.ticketflow.venue;

/**
 * Implemented by features that reference venues (events). Lets the venue feature refuse to delete a
 * venue that is in use without depending on those features, which already depend on it.
 */
public interface VenueUsageCheck {

    boolean isVenueUsed(Long venueId);
}
