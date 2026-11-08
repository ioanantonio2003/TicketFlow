package com.ticketflow.venue.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** The layout is described, not listed seat by seat: each section has rows, each row a seat count. */
public record CreateVenueRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 255) String address,
        @NotEmpty @Size(max = 50) List<@Valid SectionRequest> sections) {
}
