package com.ticketflow.event.dto;

import com.ticketflow.event.SeatStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Compact seat map: section and price appear once per section, row label once per row. Each seat is
 * only its event-seat id (what a customer sends to hold it), its number and its status.
 */
public record SeatMapResponse(Long eventId, List<Section> sections) {

    public record Section(Long sectionId, String name, BigDecimal price, List<Row> rows) {
    }

    public record Row(String label, List<Seat> seats) {
    }

    public record Seat(Long id, int number, SeatStatus status) {
    }
}
