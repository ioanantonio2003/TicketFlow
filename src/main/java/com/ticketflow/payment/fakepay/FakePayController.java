package com.ticketflow.payment.fakepay;

import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.payment.dto.WebhookPayload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The simulated payment provider. It lives in this application only for the demo; the rest of the code talks
 * to it over HTTP like to any external provider, and it answers back with a signed webhook.
 */
@RestController
@RequestMapping("/fakepay/payments")
@RequiredArgsConstructor
class FakePayController {

    record CreateRequest(@NotNull @Positive BigDecimal amount, @NotBlank String reference) {
    }

    record CreateResponse(String providerPaymentId, String payUrl) {
    }

    private final FakePayService fakePay;

    /** Called by our checkout. */
    @PostMapping
    ResponseEntity<CreateResponse> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).body(fakePay.create(request.amount(), request.reference()));
    }

    /** Called by our refund flow. Idempotent: a second refund of the same payment answers 200 too. */
    @PostMapping("/{id}/refund")
    ResponseEntity<Void> refund(@PathVariable String id) {
        fakePay.refund(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Plays the customer paying (or failing to). Answers 202 at once; the webhook is sent in the background,
     * like a real provider notifying us later.
     */
    @PostMapping("/{id}/complete")
    ResponseEntity<Void> complete(@PathVariable String id, @RequestParam String outcome) {
        String webhookOutcome = switch (outcome.toLowerCase()) {
            case "success" -> WebhookPayload.SUCCESS;
            case "failure" -> WebhookPayload.FAILURE;
            default -> throw new BadRequestException("outcome must be success or failure.");
        };
        fakePay.complete(id, webhookOutcome);
        return ResponseEntity.accepted().build();
    }
}
