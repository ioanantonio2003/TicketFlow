package com.ticketflow.ticket.dto;

import com.ticketflow.ticket.TicketStatus;
import java.time.Instant;

/** A customer's own ticket; {@code code} is what the QR code contains. */
public record TicketResponse(Long id, Long orderId, Long eventId, String eventTitle, String section, String row,
                             int seatNumber, TicketStatus status, String code, Instant usedAt) {
}
