package com.ticketflow.event.dto;

import com.ticketflow.event.EventCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** The owner is the caller and the status is always DRAFT: neither can be sent by the client. */
public record CreateEventRequest(
        @NotNull Long venueId,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotNull EventCategory category,
        @NotNull Instant startsAt) {
}
