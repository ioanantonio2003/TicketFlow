package com.ticketflow.booking;

import com.ticketflow.booking.dto.OrderItemResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public read-only door for other features (tickets): which seats does an order contain? */
@Service
@RequiredArgsConstructor
public class OrderSeats {

    private final OrderReadRepository orderReadRepository;

    @Transactional(readOnly = true)
    public List<OrderItemResponse> of(Long orderId) {
        return orderReadRepository.itemsByOrder(List.of(orderId)).getOrDefault(orderId, List.of());
    }
}
