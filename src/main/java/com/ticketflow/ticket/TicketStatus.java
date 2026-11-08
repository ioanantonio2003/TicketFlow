package com.ticketflow.ticket;

/** VALID -> USED (scanned at the entrance); VALID -> CANCELLED (refund, Step 12). */
public enum TicketStatus {
    VALID,
    USED,
    CANCELLED
}
