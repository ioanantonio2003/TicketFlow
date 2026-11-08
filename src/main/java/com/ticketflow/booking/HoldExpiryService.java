package com.ticketflow.booking;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases the seats of PENDING orders whose hold ran out. Safe to run on several instances at once:
 * rows are claimed with {@code for update skip locked}, and both writes are conditional (the order must
 * still be PENDING, the seat still HELD), so repeating or overlapping runs change nothing twice.
 */
@Service
@RequiredArgsConstructor
class HoldExpiryService {

    private final OrderRepository orderRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final ApplicationEventPublisher events;
    private final HoldProperties properties;
    private final Clock clock;

    /** One batch in its own transaction. Returns how many orders were expired (0 means nothing is left). */
    @Transactional
    int expireBatch() {
        Instant now = clock.instant();
        List<Order> expired = orderRepository.claimExpired(now, properties.expiryBatchSize());
        if (expired.isEmpty()) {
            return 0;
        }
        expired.forEach(Order::expire);
        Map<Long, List<Long>> released = seatHoldRepository.releaseSeats(expired.stream().map(Order::getId).toList());
        // delivered to AFTER_COMMIT listeners only if this transaction commits
        released.forEach((eventId, seatIds) -> events.publishEvent(new SeatsReleased(eventId, seatIds)));
        return expired.size();
    }
}
