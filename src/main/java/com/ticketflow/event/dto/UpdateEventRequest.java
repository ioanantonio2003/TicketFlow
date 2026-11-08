package com.ticketflow.event.dto;

import com.ticketflow.event.EventCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Full replacement (PUT) of the editable fields. The venue cannot change, because prices depend on its sections. */
public record UpdateEventRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @NotNull EventCategory category,
        @NotNull Instant startsAt) {
}
