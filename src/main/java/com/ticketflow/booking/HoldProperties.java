package com.ticketflow.booking;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.booking.*}: how long a hold lasts, how many seats one customer may have per event, and how
 * the job that releases expired holds is paced.
 */
@Validated
@ConfigurationProperties("app.booking")
public record HoldProperties(@NotNull Duration holdDuration, @Min(1) int maxSeatsPerEvent,
                             @NotNull Duration expiryCheckInterval, @Min(1) int expiryBatchSize) {
}
