package com.ticketflow.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class HoldExpiryJob {

    private final HoldExpiryService expiryService;
    private final HoldProperties properties;

    /** Fixed delay: the next run starts after the previous one ended, so runs of one instance never overlap. */
    @Scheduled(initialDelayString = "${app.booking.expiry-check-interval}",
            fixedDelayString = "${app.booking.expiry-check-interval}")
    void releaseExpiredHolds() {
        int total = 0;
        int batch;
        do {
            batch = expiryService.expireBatch(); // each batch is its own transaction
            total += batch;
        } while (batch == properties.expiryBatchSize());
        if (total > 0) {
            log.info("Expired {} order(s) and released their seats", total);
        }
    }
}
