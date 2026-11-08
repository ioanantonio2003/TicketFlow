package com.ticketflow.booking.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The customer picks event seats (the ids from the seat map). Everything else, including the customer,
 * the price and the status, is decided by the server. The upper size is only a guard against absurd
 * payloads; the real limit is {@code app.booking.max-seats-per-event}.
 */
public record CreateOrderRequest(
        @NotNull Long eventId,
        @NotEmpty @Size(max = 100) List<@NotNull Long> seatIds) {
}
