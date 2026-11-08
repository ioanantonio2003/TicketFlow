package com.ticketflow.payment;

import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.payment.dto.WebhookPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Public endpoint called by the payment provider; authenticated by the signature, not by a token. */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
class WebhookController {

    private final WebhookVerifier verifier;
    private final PaymentSettlement settlement;
    private final JsonMapper jsonMapper;

    /**
     * The body is taken as a raw string because the signature covers the exact bytes that were sent; parsing
     * and re-serializing could change them. The body is parsed only after the signature is verified.
     */
    @PostMapping("/webhook")
    ResponseEntity<Void> receive(
            @RequestHeader(value = WebhookSignature.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = WebhookSignature.SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String body) {
        verifier.verify(timestamp, signature, body);
        WebhookPayload payload;
        try {
            payload = jsonMapper.readValue(body, WebhookPayload.class);
        } catch (JacksonException e) {
            throw new BadRequestException("The webhook body is not valid.");
        }
        if (payload == null || payload.providerPaymentId() == null || payload.outcome() == null) {
            throw new BadRequestException("The webhook body is not valid.");
        }
        settlement.apply(payload);
        return ResponseEntity.ok().build();
    }
}
