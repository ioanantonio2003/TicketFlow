package com.ticketflow.payment;

import com.ticketflow.booking.OrderPayments;
import com.ticketflow.booking.SettleResult;
import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.payment.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies a verified webhook to the payment and its order, in one transaction. */
@Slf4j
@Service
@RequiredArgsConstructor
class PaymentSettlement {

    private final PaymentRepository paymentRepository;
    private final OrderPayments orderPayments;

    /**
     * Idempotent by provider payment id: the payment row is locked, and a payment that is no longer PENDING
     * means this delivery is a duplicate (providers retry), so nothing changes and the answer is still 200.
     */
    @Transactional
    void apply(WebhookPayload payload) {
        Payment payment = paymentRepository.findByProviderPaymentIdForUpdate(payload.providerPaymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found."));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("Duplicate webhook for payment {} ({}): ignored", payment.getId(), payment.getStatus());
            return;
        }
        if (WebhookPayload.FAILURE.equals(payload.outcome())) {
            payment.fail(); // the order stays PENDING: the customer can pay again until the hold runs out
        } else if (WebhookPayload.SUCCESS.equals(payload.outcome())) {
            if (orderPayments.settle(payment.getOrderId()) == SettleResult.PAID) {
                payment.succeed();
            } else {
                // the money arrived after the hold expired (or the order was cancelled / already paid)
                payment.refund();
                log.warn("Payment {} succeeded but order {} cannot be paid any more: marked REFUNDED",
                        payment.getId(), payment.getOrderId());
            }
        } else {
            throw new BadRequestException("Unknown outcome.");
        }
    }
}
