package com.ticketflow.reporting.dto;

import java.math.BigDecimal;

public record SectionStats(Long sectionId, String name, SeatCounts seats, BigDecimal occupancyPercent,
                           BigDecimal revenue) {
}
