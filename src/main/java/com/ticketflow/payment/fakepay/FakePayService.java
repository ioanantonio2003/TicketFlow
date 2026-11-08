package com.ticketflow.payment.fakepay;

import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.payment.PaymentProperties;
import com.ticketflow.payment.WebhookSignature;
import com.ticketflow.payment.dto.WebhookPayload;
import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The provider's memory and its webhook sender. Payments are kept in memory (lost on restart, which is fine
 * for a simulation). The webhook goes out on another thread through a real HTTP call, signed with the shared
 * secret, so our endpoint is exercised exactly as it would be by a real provider.
 */
@Slf4j
@Service
class FakePayService {

    private record FakePayment(BigDecimal amount, String reference, boolean completed) {
    }

    private final Map<String, FakePayment> payments = new ConcurrentHashMap<>();
    private final ExecutorService webhookSender = Executors.newVirtualThreadPerTaskExecutor();
    private final PaymentProperties properties;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final RestClient restClient;

    FakePayService(PaymentProperties properties, JsonMapper jsonMapper, Clock clock) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    FakePayController.CreateResponse create(BigDecimal amount, String reference) {
        String id = "fp_" + UUID.randomUUID().toString().replace("-", "");
        payments.put(id, new FakePayment(amount, reference, false));
        return new FakePayController.CreateResponse(id,
                properties.baseUrl() + "/fakepay/payments/" + id + "/complete");
    }

    /** A payment can be completed once; the webhook is then sent asynchronously. */
    void complete(String id, String outcome) {
        FakePayment payment = payments.get(id);
        if (payment == null) {
            throw new ResourceNotFoundException("Payment not found.");
        }
        // atomic "complete once": replace only succeeds if the stored value is still the not-completed one
        if (payment.completed() || !payments.replace(id, payment,
                new FakePayment(payment.amount(), payment.reference(), true))) {
            throw new ConflictException("The payment was already completed.");
        }
        webhookSender.execute(() -> sendWebhook(new WebhookPayload(id, outcome)));
    }

    private void sendWebhook(WebhookPayload payload) {
        try {
            String body = jsonMapper.writeValueAsString(payload);
            String timestamp = String.valueOf(clock.instant().getEpochSecond());
            String signature = WebhookSignature.sign(properties.webhookSecret(), timestamp, body);
            restClient.post().uri(properties.baseUrl() + "/api/payments/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(WebhookSignature.TIMESTAMP_HEADER, timestamp)
                    .header(WebhookSignature.SIGNATURE_HEADER, signature)
                    .body(body)
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            // a real provider would retry; the simulation only logs
            log.warn("FakePay could not deliver the webhook for {}: {}", payload.providerPaymentId(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        webhookSender.shutdown();
    }
}
