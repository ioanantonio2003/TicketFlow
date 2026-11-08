package com.ticketflow.booking;

/** Outcome of turning a successful payment into a paid order. */
public enum SettleResult {
    /** The held seats are now SOLD and the order is PAID. */
    PAID,
    /** The order is no longer PENDING (expired, cancelled, already paid): the money must be given back. */
    TOO_LATE
}
