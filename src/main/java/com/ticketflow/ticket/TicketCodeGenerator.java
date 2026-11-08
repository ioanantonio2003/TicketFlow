package com.ticketflow.ticket;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * Makes ticket codes: 128 random bits from a cryptographically secure generator, written as URL-safe Base64
 * without padding (22 characters). The code is the only secret on the QR, so it must not be guessable or derived
 * from anything (no ids, no counters, no timestamps).
 */
@Component
class TicketCodeGenerator {

    private static final int BYTES = 16;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();

    String next() {
        byte[] bytes = new byte[BYTES];
        random.nextBytes(bytes);
        return encoder.encodeToString(bytes);
    }
}
