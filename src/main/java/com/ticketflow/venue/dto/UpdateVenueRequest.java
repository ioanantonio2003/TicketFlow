package com.ticketflow.venue.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** PATCH body: absent fields stay unchanged. The layout cannot be changed here. */
public record UpdateVenueRequest(
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String city,
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String address) {
}
