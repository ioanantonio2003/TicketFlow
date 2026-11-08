package com.ticketflow.booking;

import com.ticketflow.event.EventCancelled;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * When an event is cancelled (after that transaction committed), no new order can start (the event is no longer
 * PUBLISHED), and the pending ones are cancelled in batches. The paid ones are refunded by the payment feature.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class EventCancellationListener {

    private final EventCancellationService cancellationService;
    private final HoldProperties properties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onEventCancelled(EventCancelled cancelled) {
        int total = 0;
        try {
            int batch;
            do {
                batch = cancellationService.cancelPendingBatch(cancelled.eventId());
                total += batch;
            } while (batch == properties.expiryBatchSize());
        } catch (RuntimeException e) {
            log.error("Could not cancel all pending orders of event {}", cancelled.eventId(), e);
        }
        log.info("Cancelled {} pending order(s) of cancelled event {}", total, cancelled.eventId());
    }
}
