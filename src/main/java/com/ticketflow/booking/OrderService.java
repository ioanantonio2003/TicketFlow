package com.ticketflow.booking;

import com.ticketflow.booking.dto.CreateOrderRequest;
import com.ticketflow.booking.dto.OrderItemResponse;
import com.ticketflow.booking.dto.OrderResponse;
import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.event.EventCatalog;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every method takes the id of the calling customer; someone else's order is "not found". */
@Service
@RequiredArgsConstructor
class OrderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final SeatHoldRepository seatHoldRepository;
    private final OrderReadRepository orderReadRepository;
    private final EventCatalog eventCatalog;
    private final HoldProperties properties;
    private final Clock clock;

    /**
     * Holds the requested seats for the customer. All or nothing: if any seat is not available the
     * exception rolls back the order and the whole transaction, so no seat stays held.
     */
    @Transactional
    OrderResponse create(Long customerId, CreateOrderRequest request) {
        List<Long> seatIds = request.seatIds();
        validateSeatCount(seatIds);
        Instant now = clock.instant();
        eventCatalog.requireBookable(request.eventId(), now); // 404 if not published, 422 if already started

        // From here until the transaction ends, this customer's other requests for this event wait.
        seatHoldRepository.lockCustomerForEvent(customerId, request.eventId());
        long alreadyHeld = orderItemRepository.countActiveSeats(customerId, request.eventId(), now);
        if (alreadyHeld + seatIds.size() > properties.maxSeatsPerEvent()) {
            throw new BusinessRuleException("You can have at most " + properties.maxSeatsPerEvent()
                    + " seats per event (you already have " + alreadyHeld + ").");
        }

        Instant expiresAt = now.plus(properties.holdDuration());
        Order order = orderRepository.save(Order.pending(customerId, request.eventId(), expiresAt));

        int held = seatHoldRepository.holdSeats(request.eventId(), seatIds, order.getId(), expiresAt);
        if (held != seatIds.size()) {
            throw new SeatsUnavailableException(); // rolls back the order and any seat held above
        }

        Map<Long, BigDecimal> prices = seatHoldRepository.pricesOfHeldSeats(order.getId());
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<Long, BigDecimal> seat : prices.entrySet()) {
            orderItemRepository.save(new OrderItem(order, seat.getKey(), seat.getValue()));
            total = total.add(seat.getValue());
        }
        order.assignTotal(total);
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    OrderResponse get(Long customerId, Long orderId) {
        Order order = orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found."));
        return toResponse(order);
    }

    @Transactional(readOnly = true)
    PageResponse<OrderResponse> listMine(Long customerId, Pageable pageable) {
        Page<Order> orders = orderRepository.findByCustomerId(customerId, pageable);
        // one query describes the seats of the whole page
        Map<Long, List<OrderItemResponse>> items = orderReadRepository.itemsByOrder(
                orders.getContent().stream().map(Order::getId).toList());
        return PageResponse.from(orders.map(order -> toResponse(order, items.getOrDefault(order.getId(), List.of()))));
    }

    private void validateSeatCount(List<Long> seatIds) {
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new BusinessRuleException("The same seat was selected more than once.");
        }
        if (seatIds.size() > properties.maxSeatsPerEvent()) {
            throw new BusinessRuleException(
                    "You can book at most " + properties.maxSeatsPerEvent() + " seats at a time.");
        }
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = orderReadRepository.itemsByOrder(List.of(order.getId()))
                .getOrDefault(order.getId(), new ArrayList<>());
        return toResponse(order, items);
    }

    private static OrderResponse toResponse(Order order, List<OrderItemResponse> items) {
        return new OrderResponse(order.getId(), order.getEventId(), order.getStatus(), order.getHoldExpiresAt(),
                order.getTotalAmount(), items);
    }
}
