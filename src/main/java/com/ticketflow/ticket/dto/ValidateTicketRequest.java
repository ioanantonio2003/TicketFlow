package com.ticketflow.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ValidateTicketRequest(@NotBlank @Size(max = 64) String code) {
}
