package com.ticketflow.booking.dto;

import java.math.BigDecimal;

public record OrderItemResponse(Long eventSeatId, String section, String row, int seatNumber, BigDecimal price) {
}
