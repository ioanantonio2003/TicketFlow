package com.ticketflow.booking;

import java.math.BigDecimal;

/** What the payment feature needs to know about an order it is about to charge. */
public record PayableOrder(Long orderId, Long eventId, BigDecimal amount) {
}
