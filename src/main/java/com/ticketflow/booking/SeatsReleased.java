package com.ticketflow.booking;

import java.util.List;

/**
 * Seats of an event went back to AVAILABLE (a hold expired or an order was cancelled). Other features
 * (waitlist, live seat updates, cache eviction) listen with {@code @TransactionalEventListener}, whose
 * default phase AFTER_COMMIT means they only see releases that really happened.
 */
public record SeatsReleased(Long eventId, List<Long> seatIds) {
}
