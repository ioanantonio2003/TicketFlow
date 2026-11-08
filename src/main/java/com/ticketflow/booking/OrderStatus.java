package com.ticketflow.booking;

/** PENDING -> PAID -> REFUNDED; PENDING -> EXPIRED; PENDING -> CANCELLED. */
public enum OrderStatus {
    PENDING,
    PAID,
    EXPIRED,
    CANCELLED,
    REFUNDED
}
