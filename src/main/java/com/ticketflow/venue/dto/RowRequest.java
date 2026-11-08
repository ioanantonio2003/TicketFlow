package com.ticketflow.venue.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A row with a label (e.g. "1" or "A") and how many seats it has; seats are numbered 1..seats. */
public record RowRequest(
        @NotBlank @Size(max = 20) String label,
        @NotNull @Min(1) @Max(1000) Integer seats) {
}
