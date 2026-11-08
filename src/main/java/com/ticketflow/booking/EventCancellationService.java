package com.ticketflow.booking;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Cancels the PENDING orders of a cancelled event, one batch per transaction. */
@Service
@RequiredArgsConstructor
class EventCancellationService {

    private final OrderRepository orderRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final ApplicationEventPublisher events;
    private final HoldProperties properties;

    /** Returns how many orders were cancelled; 0 means none are left. */
    @Transactional(propagation = Propagation.REQUIRES_NEW) // called from an AFTER_COMMIT listener: see RefundService
    int cancelPendingBatch(Long eventId) {
        List<Order> claimed = orderRepository.claimPendingOfEvent(eventId, properties.expiryBatchSize());
        if (claimed.isEmpty()) {
            return 0;
        }
        claimed.forEach(Order::cancel);
        seatHoldRepository.releaseSeats(claimed.stream().map(Order::getId).toList())
                .forEach((id, seatIds) -> events.publishEvent(new SeatsReleased(id, seatIds)));
        claimed.forEach(order -> events.publishEvent(new OrderCancelled(order.getId(), order.getCustomerId(),
                order.getEventId(), CancellationReason.EVENT_CANCELLED)));
        return claimed.size();
    }
}
