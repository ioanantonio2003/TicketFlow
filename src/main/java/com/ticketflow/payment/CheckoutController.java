package com.ticketflow.payment;

import com.ticketflow.common.web.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Under /api/orders/**, so the CUSTOMER rule of SecurityConfig applies. */
@RestController
@RequiredArgsConstructor
class CheckoutController {

    private final CheckoutService checkoutService;

    /**
     * Starts the payment of a PENDING order. Retrying with the same {@code Idempotency-Key} returns the very same
     * response and creates no second payment; the same key for another order is rejected (422).
     */
    @PostMapping("/api/orders/{id}/checkout")
    ResponseEntity<String> checkout(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey) {
        StoredResponse response = checkoutService.checkout(CurrentUser.id(jwt), id, idempotencyKey);
        return ResponseEntity.status(response.statusCode()).contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
