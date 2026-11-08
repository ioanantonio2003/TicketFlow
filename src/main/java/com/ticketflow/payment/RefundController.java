package com.ticketflow.payment;

import com.ticketflow.common.web.CurrentUser;
import com.ticketflow.payment.dto.RefundResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Under /api/orders/**, so the CUSTOMER rule of SecurityConfig applies. */
@RestController
@RequiredArgsConstructor
class RefundController {

    private final RefundService refundService;

    /**
     * Refunds a PAID order until {@code app.booking.refund-deadline} before the event starts: 404 if it is not the
     * caller's, 409 if it is not paid, 422 if it is too late, 502 if the provider fails (nothing changed then).
     */
    @PostMapping("/api/orders/{id}/refund")
    RefundResponse refund(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return refundService.refundMyOrder(CurrentUser.id(jwt), id);
    }
}
