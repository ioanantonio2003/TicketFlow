package com.ticketflow.booking;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.event.EventCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The order and seat side of a refund, used by the payment feature (which owns the money side). Two steps so that
 * the remote refund call can sit between them without a database transaction around it.
 */
@Service
@RequiredArgsConstructor
public class OrderRefunds {

    private final OrderRepository orderRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final EventCatalog eventCatalog;
    private final HoldProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * Step 1, a customer asks for a refund: the order must be theirs (else 404), PAID (else 409), and the event must
     * start more than {@code app.booking.refund-deadline} from now (else 422). Exactly at the limit is still allowed.
     */
    @Transactional(readOnly = true)
    public RefundableOrder requireRefundable(Long customerId, Long orderId) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        if (order.getStatus() != OrderStatus.PAID) {
            throw new ConflictException("Only a paid order can be refunded.");
        }
        Instant lastMoment = eventCatalog.get(order.getEventId()).startsAt().minus(properties.refundDeadline());
        if (clock.instant().isAfter(lastMoment)) {
            throw new BusinessRuleException("Refunds are possible until " + properties.refundDeadline().toHours()
                    + " hours before the event starts.");
        }
        return new RefundableOrder(order.getId(), order.getEventId(), order.getTotalAmount());
    }

    /**
     * Step 2, after the provider returned the money: the order becomes REFUNDED and its SOLD seats AVAILABLE, in one
     * transaction. The order row is locked and re-checked, so two refunds of one order cannot both succeed.
     * Used for the customer's own request and, without the deadline, when the event is cancelled.
     */
    @Transactional
    public void refund(Long orderId, CancellationReason reason) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        order.refund(); // 409 unless PAID
        seatHoldRepository.releaseSoldSeats(orderId)
                .forEach((eventId, seatIds) -> events.publishEvent(new SeatsReleased(eventId, seatIds)));
        events.publishEvent(new OrderRefunded(order.getId(), order.getCustomerId(), order.getEventId(),
                order.getTotalAmount(), reason));
    }

    /** A page of an event's PAID order ids after {@code afterId}, for refunding them in batches. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<Long> paidOrderIds(Long eventId, Long afterId, int limit) {
        return orderRepository.findPaidIdsByEvent(eventId, afterId, Limit.of(limit));
    }
}
