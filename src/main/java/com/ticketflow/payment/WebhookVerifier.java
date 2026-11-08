package com.ticketflow.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/**
 * Accepts a webhook only if it was signed with our shared secret and is recent. Every failure gives the same
 * generic 401 so a caller learns nothing about which check failed.
 */
@Component
class WebhookVerifier {

    private final String secret;
    private final Duration tolerance;
    private final Clock clock;

    WebhookVerifier(PaymentProperties properties, Clock clock) {
        this.secret = properties.webhookSecret();
        this.tolerance = properties.webhookTolerance();
        this.clock = clock;
    }

    void verify(String timestamp, String signature, String body) {
        if (timestamp == null || signature == null || body == null) {
            throw reject();
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw reject();
        }
        Duration age = Duration.between(Instant.ofEpochSecond(epochSeconds), clock.instant());
        if (age.abs().compareTo(tolerance) > 0) {
            throw reject(); // too old (replay) or too far in the future (wrong clock / forged)
        }
        byte[] expected = WebhookSignature.sign(secret, timestamp, body).getBytes(StandardCharsets.UTF_8);
        // constant-time comparison: the time taken does not reveal how many characters matched
        if (!MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8))) {
            throw reject();
        }
    }

    private static BadCredentialsException reject() {
        return new BadCredentialsException("Invalid webhook signature");
    }
}
