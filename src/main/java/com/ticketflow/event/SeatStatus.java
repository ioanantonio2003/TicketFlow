package com.ticketflow.event;

/** AVAILABLE -> HELD -> SOLD; HELD -> AVAILABLE (expiry/cancel); SOLD -> AVAILABLE (refund). */
public enum SeatStatus {
    AVAILABLE,
    HELD,
    SOLD
}
