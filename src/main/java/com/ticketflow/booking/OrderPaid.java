package com.ticketflow.booking;

/**
 * An order became PAID and its seats SOLD. Published inside the settling transaction; listeners use
 * {@code @TransactionalEventListener} so they only react to payments that were really committed.
 */
public record OrderPaid(Long orderId, Long customerId, Long eventId) {
}
