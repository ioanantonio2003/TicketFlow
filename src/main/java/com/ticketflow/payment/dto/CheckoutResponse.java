package com.ticketflow.payment.dto;

import com.ticketflow.payment.PaymentStatus;
import java.math.BigDecimal;

/** The payment that was started: the customer goes to {@code payUrl} to pay it. */
public record CheckoutResponse(Long paymentId, Long orderId, PaymentStatus status, BigDecimal amount,
                               String providerPaymentId, String payUrl) {
}
