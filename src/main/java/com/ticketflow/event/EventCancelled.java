package com.ticketflow.event;

/**
 * An organizer cancelled an event. Published inside the cancelling transaction and delivered to
 * {@code @TransactionalEventListener} methods after it commits: booking cancels the pending orders, payment
 * refunds the paid ones.
 */
public record EventCancelled(Long eventId, String title) {
}
