package com.ticketflow.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;

class WebhookVerifierTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final String SECRET = "unit-test-secret-0123456789";
    private static final String BODY = "{\"providerPaymentId\":\"fp_1\",\"outcome\":\"SUCCESS\"}";

    private final WebhookVerifier verifier = new WebhookVerifier(
            new PaymentProperties("http://localhost", SECRET, Duration.ofMinutes(5)), Clock.fixed(NOW, ZoneOffset.UTC));

    private static String timestamp(Duration offsetFromNow) {
        return String.valueOf(NOW.plus(offsetFromNow).getEpochSecond());
    }

    @Test
    void acceptsAValidSignatureWithAFreshTimestamp() {
        String ts = timestamp(Duration.ZERO);

        assertThatCode(() -> verifier.verify(ts, WebhookSignature.sign(SECRET, ts, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void acceptsATimestampJustInsideTheWindow() {
        String ts = timestamp(Duration.ofMinutes(-5));

        assertThatCode(() -> verifier.verify(ts, WebhookSignature.sign(SECRET, ts, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void rejectsASignatureMadeWithAnotherSecret() {
        String ts = timestamp(Duration.ZERO);

        assertThatThrownBy(() -> verifier.verify(ts, WebhookSignature.sign("another-secret", ts, BODY), BODY))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsATimestampOlderThanFiveMinutesEvenWithAValidSignature() {
        String ts = timestamp(Duration.ofMinutes(-5).minusSeconds(1));

        assertThatThrownBy(() -> verifier.verify(ts, WebhookSignature.sign(SECRET, ts, BODY), BODY))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsATimestampFromTheFuture() {
        String ts = timestamp(Duration.ofMinutes(10));

        assertThatThrownBy(() -> verifier.verify(ts, WebhookSignature.sign(SECRET, ts, BODY), BODY))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsABodyThatWasChangedAfterSigning() {
        String ts = timestamp(Duration.ZERO);
        String signature = WebhookSignature.sign(SECRET, ts, BODY);

        assertThatThrownBy(() -> verifier.verify(ts, signature, BODY.replace("SUCCESS", "FAILURE")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsAnOldSignatureReusedWithAFreshTimestamp() {
        String old = timestamp(Duration.ofMinutes(-30));
        String signature = WebhookSignature.sign(SECRET, old, BODY);

        assertThatThrownBy(() -> verifier.verify(timestamp(Duration.ZERO), signature, BODY))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsMissingOrMalformedInput() {
        String ts = timestamp(Duration.ZERO);
        String signature = WebhookSignature.sign(SECRET, ts, BODY);

        assertThatThrownBy(() -> verifier.verify(null, signature, BODY)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> verifier.verify(ts, null, BODY)).isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> verifier.verify("not-a-number", signature, BODY))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> verifier.verify(ts, "", BODY)).isInstanceOf(BadCredentialsException.class);
    }
}
