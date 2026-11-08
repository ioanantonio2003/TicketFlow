package com.ticketflow.reporting.dto;

import com.ticketflow.booking.OrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Sales of one event. {@code occupancyPercent} = sold / total seats (held seats are not sold yet).
 * {@code revenue} = money of PAID orders only (refunded and cancelled orders are not revenue).
 * {@code ordersByStatus} has every status, with 0 where there are none.
 */
public record EventStatsResponse(Long eventId, String title, String status, SeatCounts seats,
                                 BigDecimal occupancyPercent, BigDecimal revenue,
                                 Map<OrderStatus, Long> ordersByStatus, List<SectionStats> sections) {
}
