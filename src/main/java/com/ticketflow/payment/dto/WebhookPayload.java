package com.ticketflow.payment.dto;

/** The JSON body of the provider's webhook. {@code outcome} is SUCCESS or FAILURE. */
public record WebhookPayload(String providerPaymentId, String outcome) {

    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";
}
