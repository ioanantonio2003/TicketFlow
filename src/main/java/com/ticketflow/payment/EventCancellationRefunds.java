package com.ticketflow.payment;

import com.ticketflow.booking.HoldProperties;
import com.ticketflow.booking.OrderRefunds;
import com.ticketflow.event.EventCancelled;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Refunds every paid order of a cancelled event, after the cancellation committed. Orders are read in batches by
 * id (a cursor), and each order is refunded on its own: one failure is logged and the others still go through.
 * A failed order stays PAID, so it can be refunded by the customer's own refund request meanwhile.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class EventCancellationRefunds {

    private final OrderRefunds orderRefunds;
    private final RefundService refundService;
    private final HoldProperties properties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onEventCancelled(EventCancelled cancelled) {
        long afterId = 0;
        int refunded = 0;
        int failed = 0;
        List<Long> batch;
        do {
            batch = orderRefunds.paidOrderIds(cancelled.eventId(), afterId, properties.expiryBatchSize());
            for (Long orderId : batch) {
                try {
                    refundService.refundBecauseEventWasCancelled(orderId);
                    refunded++;
                } catch (RuntimeException e) {
                    failed++;
                    log.error("Could not refund order {} of cancelled event {}", orderId, cancelled.eventId(), e);
                }
                afterId = orderId;
            }
        } while (batch.size() == properties.expiryBatchSize());
        log.info("Event {} cancelled: {} order(s) refunded, {} failed", cancelled.eventId(), refunded, failed);
    }
}
