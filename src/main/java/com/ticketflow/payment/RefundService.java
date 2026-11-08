package com.ticketflow.payment;

import com.ticketflow.booking.CancellationReason;
import com.ticketflow.booking.OrderRefunds;
import com.ticketflow.booking.RefundableOrder;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.payment.dto.RefundResponse;
import java.math.BigDecimal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gives the money of a paid order back. Like checkout it is NOT one transaction: the remote refund call to the
 * provider must not run inside a database transaction. Order of steps, and why:
 * <ol>
 *   <li>check the rules (read only);</li>
 *   <li>ask the provider to refund (no transaction); if it fails nothing has changed and the customer can retry;</li>
 *   <li>one transaction: order REFUNDED, seats AVAILABLE, tickets cancelled (via events), payment REFUNDED.</li>
 * </ol>
 * If step 3 fails after step 2 the provider has refunded but we still show PAID; repeating the request is safe
 * because the provider treats a repeated refund of one payment as a no-op, then step 3 runs again.
 */
@Slf4j
@Service
class RefundService {

    private final OrderRefunds orderRefunds;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;

    /**
     * REQUIRES_NEW because refunds are also started from an AFTER_COMMIT listener (event cancellation). There the
     * cancelling transaction is finished but still bound to the thread, and a plain transaction would join it: the
     * first query would fail with "No active transaction".
     */
    RefundService(OrderRefunds orderRefunds, PaymentRepository paymentRepository, PaymentGateway gateway,
                  PlatformTransactionManager transactionManager) {
        this.orderRefunds = orderRefunds;
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A customer's own request: ownership, PAID status and the refund deadline are checked first. */
    RefundResponse refundMyOrder(Long customerId, Long orderId) {
        RefundableOrder order = orderRefunds.requireRefundable(customerId, orderId);
        refund(order.orderId(), CancellationReason.CUSTOMER_REQUEST);
        return new RefundResponse(order.orderId(), order.amount(), "REFUNDED");
    }

    /** The organizer cancelled the event: refund regardless of the deadline. */
    BigDecimal refundBecauseEventWasCancelled(Long orderId) {
        return refund(orderId, CancellationReason.EVENT_CANCELLED);
    }

    private BigDecimal refund(Long orderId, CancellationReason reason) {
        Payment payment = paymentRepository.findFirstByOrderIdAndStatus(orderId, PaymentStatus.SUCCEEDED)
                .orElseThrow(() -> new ConflictException("The order has no payment to refund."));
        gateway.refund(payment.getProviderPaymentId()); // remote, outside any transaction
        tx.executeWithoutResult(status -> {
            orderRefunds.refund(orderId, reason); // 409 if another request already refunded the order
            paymentRepository.findById(payment.getId()).orElseThrow().refund();
        });
        log.info("Refunded order {} ({})", orderId, reason);
        return payment.getAmount();
    }
}
