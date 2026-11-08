package com.ticketflow.payment;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.config.SecurityConfig;
import com.ticketflow.config.SecurityErrorHandlers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest({CheckoutController.class, WebhookController.class, RefundController.class})
@Import({SecurityConfig.class, SecurityErrorHandlers.class})
class PaymentControllersTest {

    private static final String WEBHOOK_BODY = "{\"providerPaymentId\":\"fp_1\",\"outcome\":\"SUCCESS\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CheckoutService checkoutService;
    @MockitoBean
    private RefundService refundService;
    @MockitoBean
    private WebhookVerifier verifier;
    @MockitoBean
    private PaymentSettlement settlement;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(long id, String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(id))).authorities(new SimpleGrantedAuthority(role));
    }

    // ---- checkout ----

    @Test
    void checkoutRelaysTheStoredAnswerExactly() throws Exception {
        when(checkoutService.checkout(7L, 55L, "k1")).thenReturn(new StoredResponse(201, "{\"paymentId\":3}"));

        mockMvc.perform(post("/api/orders/55/checkout").with(as(7, "ROLE_CUSTOMER")).header("Idempotency-Key", "k1"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"paymentId\":3}"));
    }

    @Test
    void checkoutWithoutTheIdempotencyKeyIs400() throws Exception {
        mockMvc.perform(post("/api/orders/55/checkout").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutIsForCustomersOnly() throws Exception {
        mockMvc.perform(post("/api/orders/55/checkout").header("Idempotency-Key", "k1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders/55/checkout").with(as(7, "ROLE_ORGANIZER")).header("Idempotency-Key", "k1"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(checkoutService);
    }

    @Test
    void checkoutConflictsAreProblemDetails() throws Exception {
        when(checkoutService.checkout(7L, 55L, "k1")).thenThrow(new ConflictException("The order can no longer be paid."));

        mockMvc.perform(post("/api/orders/55/checkout").with(as(7, "ROLE_CUSTOMER")).header("Idempotency-Key", "k1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The order can no longer be paid."));
    }

    // ---- webhook ----

    @Test
    void aVerifiedWebhookIsAppliedWithoutAnyUserToken() throws Exception {
        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-FakePay-Timestamp", "123").header("X-FakePay-Signature", "sig")
                        .content(WEBHOOK_BODY))
                .andExpect(status().isOk());

        verify(verifier).verify("123", "sig", WEBHOOK_BODY);
        verify(settlement).apply(any());
    }

    @Test
    void aWebhookWithABadSignatureIs401AndChangesNothing() throws Exception {
        doThrow(new BadCredentialsException("Invalid webhook signature")).when(verifier).verify(any(), any(), any());

        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-FakePay-Timestamp", "123").header("X-FakePay-Signature", "bad")
                        .content(WEBHOOK_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication failed."));

        verifyNoInteractions(settlement);
    }

    @Test
    void aSignedButMalformedBodyIs400() throws Exception {
        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-FakePay-Timestamp", "123").header("X-FakePay-Signature", "sig")
                        .content("not json"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-FakePay-Timestamp", "123").header("X-FakePay-Signature", "sig")
                        .content("{\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(settlement);
    }

    @Test
    void theHeadersAreOptionalForTheFrameworkSoTheVerifierDecides() throws Exception {
        doThrow(new BadCredentialsException("Invalid webhook signature")).when(verifier)
                .verify(eq(null), eq(null), any());

        mockMvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(WEBHOOK_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ---- refund ----

    @Test
    void customerRefundsAnOrder() throws Exception {
        when(refundService.refundMyOrder(7L, 55L))
                .thenReturn(new com.ticketflow.payment.dto.RefundResponse(55L, new java.math.BigDecimal("30.00"), "REFUNDED"));

        mockMvc.perform(post("/api/orders/55/refund").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.amount").value(30.00));
    }

    @Test
    void refundErrorsAreProblemDetails() throws Exception {
        when(refundService.refundMyOrder(7L, 55L)).thenThrow(new com.ticketflow.common.exception.BusinessRuleException(
                "Refunds are possible until 24 hours before the event starts."));
        when(refundService.refundMyOrder(7L, 56L)).thenThrow(new ConflictException("Only a paid order can be refunded."));
        when(refundService.refundMyOrder(7L, 57L)).thenThrow(
                new com.ticketflow.common.exception.ExternalServiceException("The refund could not be completed."));

        mockMvc.perform(post("/api/orders/55/refund").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(post("/api/orders/56/refund").with(as(7, "ROLE_CUSTOMER"))).andExpect(status().isConflict());
        mockMvc.perform(post("/api/orders/57/refund").with(as(7, "ROLE_CUSTOMER"))).andExpect(status().isBadGateway());
    }

    @Test
    void refundIsForCustomersOnly() throws Exception {
        mockMvc.perform(post("/api/orders/55/refund")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders/55/refund").with(as(7, "ROLE_ORGANIZER"))).andExpect(status().isForbidden());
        verifyNoInteractions(refundService);
    }
}
