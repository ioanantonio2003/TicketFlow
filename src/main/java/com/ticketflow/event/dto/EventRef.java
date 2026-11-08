package com.ticketflow.event.dto;

import java.time.Instant;

/** What other features may know about an event. */
public record EventRef(Long id, String title, Instant startsAt) {
}
