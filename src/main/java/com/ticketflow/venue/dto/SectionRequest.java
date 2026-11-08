package com.ticketflow.venue.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SectionRequest(
        @NotBlank @Size(max = 100) String name,
        @NotEmpty @Size(max = 100) List<@Valid RowRequest> rows) {
}
