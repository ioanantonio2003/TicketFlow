package com.ticketflow.payment.dto;

import java.math.BigDecimal;

/** The order was refunded: the money goes back to the customer. */
public record RefundResponse(Long orderId, BigDecimal amount, String status) {
}
