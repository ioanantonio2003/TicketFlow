package com.ticketflow.payment;

import java.math.BigDecimal;

/** The payment provider as our checkout sees it. The only implementation is the simulated FakePay. */
public interface PaymentGateway {

    /** What the provider answered: its own id for the payment and where the customer pays. */
    record ProviderPayment(String providerPaymentId, String payUrl) {
    }

    /** Remote call: never invoke it inside a database transaction. Throws ExternalServiceException on failure. */
    ProviderPayment createPayment(BigDecimal amount, String reference);
}
