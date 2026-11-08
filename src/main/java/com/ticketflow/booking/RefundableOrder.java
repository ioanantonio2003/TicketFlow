package com.ticketflow.booking;

import java.math.BigDecimal;

/** A PAID order that may be refunded now. */
public record RefundableOrder(Long orderId, Long eventId, BigDecimal amount) {
}
