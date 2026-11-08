package com.ticketflow.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** The full set of prices of an event: one per section. Sections left out end up without a price. */
public record SetPricesRequest(@NotEmpty @Size(max = 50) List<@Valid SectionPrice> prices) {

    public record SectionPrice(
            @NotNull Long sectionId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal price) {
    }
}
