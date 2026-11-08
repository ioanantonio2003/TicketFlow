package com.ticketflow.booking;

import java.math.BigDecimal;

/**
 * A PAID order was refunded and its seats are AVAILABLE again. Published inside the refunding transaction: the
 * ticket feature cancels the tickets synchronously in that same transaction (plain {@code @EventListener}), so a
 * refunded order never keeps valid tickets; notifications wait for the commit.
 */
public record OrderRefunded(Long orderId, Long customerId, Long eventId, BigDecimal amount,
                            CancellationReason reason) {
}
