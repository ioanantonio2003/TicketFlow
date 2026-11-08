package com.ticketflow.payment;

/** PENDING -> SUCCEEDED | FAILED; SUCCEEDED -> REFUNDED comes with refunds, REFUNDED is also used when money arrived too late. */
public enum PaymentStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
