package com.ticketflow.notification;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code app.notification.*}: the sender address, and a switch to turn sending off (used by most tests). */
@Validated
@ConfigurationProperties("app.notification")
public record NotificationProperties(@NotBlank String from, boolean enabled) {
}
