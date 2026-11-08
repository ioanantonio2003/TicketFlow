package com.ticketflow.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record EventTotals(Long eventId, String title, String status, Instant startsAt, long totalSeats,
                          long soldSeats, long heldSeats, BigDecimal occupancyPercent, long paidOrders,
                          BigDecimal revenue) {
}
