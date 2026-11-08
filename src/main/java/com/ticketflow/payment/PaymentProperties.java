package com.ticketflow.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.payment.*}: where the simulated provider is reachable, the secret that signs its webhooks, and how
 * old a webhook timestamp may be.
 */
@Validated
@ConfigurationProperties("app.payment")
public record PaymentProperties(@NotBlank String baseUrl, @NotBlank String webhookSecret,
                                @NotNull Duration webhookTolerance) {
}
