package com.ticketflow.booking.dto;

import com.ticketflow.booking.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(Long id, Long eventId, OrderStatus status, Instant holdExpiresAt, BigDecimal totalAmount,
                            List<OrderItemResponse> items) {
}
