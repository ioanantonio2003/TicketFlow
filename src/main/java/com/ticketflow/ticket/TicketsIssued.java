package com.ticketflow.ticket;

import java.util.List;

/**
 * The tickets of a paid order now exist. Delivered after the issuing transaction committed, so a listener (the
 * confirmation email) can rely on the tickets being readable.
 */
public record TicketsIssued(Long orderId, Long customerId, Long eventId, String eventTitle,
                            List<IssuedTicket> tickets) {

    /** One admission: the seat in words and the code that is also in its QR. */
    public record IssuedTicket(String section, String row, int seatNumber, String code) {
    }
}
