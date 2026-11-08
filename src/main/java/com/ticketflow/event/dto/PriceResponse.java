package com.ticketflow.event.dto;

import java.math.BigDecimal;

public record PriceResponse(Long sectionId, BigDecimal price) {
}
