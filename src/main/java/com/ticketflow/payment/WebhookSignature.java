package com.ticketflow.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signature scheme shared by the provider (signs) and our webhook endpoint (verifies):
 * {@code hex(HMAC-SHA256(secret, timestamp + "." + body))}. The timestamp is part of the signed text, so an
 * attacker cannot replay an old body with a fresh timestamp.
 */
public final class WebhookSignature {

    public static final String TIMESTAMP_HEADER = "X-FakePay-Timestamp";
    public static final String SIGNATURE_HEADER = "X-FakePay-Signature";

    private WebhookSignature() {
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
