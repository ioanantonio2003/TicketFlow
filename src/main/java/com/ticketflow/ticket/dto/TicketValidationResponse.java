package com.ticketflow.ticket.dto;

import java.time.Instant;

/** What the person at the entrance sees after a successful scan. The code is not repeated. */
public record TicketValidationResponse(Long ticketId, Long eventId, String eventTitle, String section, String row,
                                       int seatNumber, Instant usedAt) {
}
