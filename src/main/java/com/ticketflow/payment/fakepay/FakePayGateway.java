package com.ticketflow.payment.fakepay;

import com.ticketflow.common.exception.ExternalServiceException;
import com.ticketflow.payment.PaymentGateway;
import com.ticketflow.payment.PaymentProperties;
import java.math.BigDecimal;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Our side of the integration: an HTTP client for the provider, with timeouts. */
@Slf4j
@Component
class FakePayGateway implements PaymentGateway {

    private final RestClient restClient;

    FakePayGateway(PaymentProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
    }

    @Override
    public ProviderPayment createPayment(BigDecimal amount, String reference) {
        try {
            FakePayController.CreateResponse response = restClient.post().uri("/fakepay/payments")
                    .body(new FakePayController.CreateRequest(amount, reference))
                    .retrieve().body(FakePayController.CreateResponse.class);
            if (response == null || response.providerPaymentId() == null) {
                throw new ExternalServiceException("The payment provider sent an invalid answer.");
            }
            return new ProviderPayment(response.providerPaymentId(), response.payUrl());
        } catch (RestClientException e) {
            log.warn("Payment provider call failed: {}", e.getMessage()); // details only in the log
            throw new ExternalServiceException("The payment provider is not available. Please try again.");
        }
    }
}
