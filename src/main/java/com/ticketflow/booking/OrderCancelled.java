package com.ticketflow.booking;

/** A PENDING order was cancelled and its seats released (by the customer, or because the event was cancelled). */
public record OrderCancelled(Long orderId, Long customerId, Long eventId, CancellationReason reason) {
}
