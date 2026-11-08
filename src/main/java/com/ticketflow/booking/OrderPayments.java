package com.ticketflow.booking;

import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only door the payment feature uses to reach orders and seats (CLAUDE.md section 5): "can this order be
 * charged?" and "the money arrived, make the order paid".
 */
@Service
@RequiredArgsConstructor
public class OrderPayments {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** The caller's own PENDING order whose hold has not run out; someone else's order is "not found". */
    @Transactional(readOnly = true)
    public PayableOrder requirePayable(Long customerId, Long orderId) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        if (order.getStatus() != OrderStatus.PENDING || !order.getHoldExpiresAt().isAfter(clock.instant())) {
            throw new ConflictException("The order can no longer be paid.");
        }
        return new PayableOrder(order.getId(), order.getEventId(), order.getTotalAmount());
    }

    /**
     * A payment succeeded. The order row is locked, so this cannot interleave with the expiry job, a
     * cancellation or a second webhook. If the order is still PENDING its seats are still HELD by it (the
     * job releases an order's seats in the same transaction that expires it), so the conditional
     * {@code HELD -> SOLD} update must change exactly as many rows as there are items; anything else is a
     * bug and rolls everything back. A PENDING order whose deadline passed a few seconds ago but was not swept
     * yet is still paid: nobody else could take its seats.
     */
    @Transactional
    public SettleResult settle(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        if (order.getStatus() != OrderStatus.PENDING) {
            return SettleResult.TOO_LATE;
        }
        long items = orderItemRepository.countByOrderId(orderId);
        int sold = seatHoldRepository.sellSeats(orderId);
        if (sold != items) {
            throw new IllegalStateException("Order " + orderId + " has " + items + " items but " + sold
                    + " held seats were sold");
        }
        order.markPaid();
        events.publishEvent(new OrderPaid(order.getId(), order.getCustomerId(), order.getEventId()));
        return SettleResult.PAID;
    }
}
