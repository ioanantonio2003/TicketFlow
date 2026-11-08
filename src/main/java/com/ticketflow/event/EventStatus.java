package com.ticketflow.event;

/** DRAFT -> PUBLISHED -> COMPLETED; DRAFT or PUBLISHED -> CANCELLED. */
public enum EventStatus {
    DRAFT,
    PUBLISHED,
    COMPLETED,
    CANCELLED
}
