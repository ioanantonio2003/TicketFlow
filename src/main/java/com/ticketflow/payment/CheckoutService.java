package com.ticketflow.payment;

import com.ticketflow.booking.OrderPayments;
import com.ticketflow.booking.PayableOrder;
import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ExternalServiceException;
import com.ticketflow.payment.PaymentGateway.ProviderPayment;
import com.ticketflow.payment.dto.CheckoutResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Starts a payment for an order, once per {@code Idempotency-Key}.
 *
 * <p>The method is deliberately NOT transactional: there are three short transactions with the remote call
 * to the provider between them, because no remote call may run inside a database transaction (it would hold
 * a connection and locks while waiting for the network).
 * <ol>
 *   <li>claim the key, check the order, create a PENDING payment;</li>
 *   <li>call the provider (no transaction);</li>
 *   <li>store the provider id and the response under the key.</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class CheckoutService {

    private static final int MAX_KEY_LENGTH = 100;

    private final OrderPayments orderPayments;
    private final PaymentRepository paymentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final PaymentGateway gateway;
    private final TransactionTemplate tx;
    private final JsonMapper jsonMapper;

    /** What the first transaction decided: replay a stored answer, or go on and call the provider. */
    private record Started(StoredResponse replay, Long paymentId, PayableOrder order) {
    }

    StoredResponse checkout(Long customerId, Long orderId, String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new BadRequestException("The Idempotency-Key header must be 1 to " + MAX_KEY_LENGTH
                    + " characters.");
        }
        String requestHash = hash("POST /api/orders/" + orderId + "/checkout");

        Started started = tx.execute(status -> start(customerId, orderId, key, requestHash));
        if (started.replay() != null) {
            return started.replay();
        }

        ProviderPayment providerPayment;
        try {
            providerPayment = gateway.createPayment(started.order().amount(), "order-" + orderId);
        } catch (ExternalServiceException e) {
            // nothing was charged: close the attempt and free the key so the client can simply try again
            tx.executeWithoutResult(status -> {
                paymentRepository.findById(started.paymentId()).orElseThrow().fail();
                idempotencyRepository.delete(customerId, key);
            });
            throw e;
        }
        return tx.execute(status -> finish(customerId, key, started, providerPayment));
    }

    private Started start(Long customerId, Long orderId, String key, String requestHash) {
        if (!idempotencyRepository.tryStart(customerId, key, requestHash)) {
            var stored = idempotencyRepository.find(customerId, key).orElseThrow();
            if (!stored.requestHash().equals(requestHash)) {
                throw new BusinessRuleException("This Idempotency-Key was already used for a different request.");
            }
            if (stored.statusCode() == null) {
                throw new ConflictException("A request with this Idempotency-Key is still being processed.");
            }
            return new Started(new StoredResponse(stored.statusCode(), stored.responseBody()), null, null);
        }
        PayableOrder order = orderPayments.requirePayable(customerId, orderId); // 404 / 409; rolls the key back
        Payment payment = paymentRepository.save(Payment.pending(order.orderId(), order.amount()));
        return new Started(null, payment.getId(), order);
    }

    private StoredResponse finish(Long customerId, String key, Started started, ProviderPayment providerPayment) {
        Payment payment = paymentRepository.findById(started.paymentId()).orElseThrow();
        payment.attachProvider(providerPayment.providerPaymentId());
        CheckoutResponse response = new CheckoutResponse(payment.getId(), started.order().orderId(),
                payment.getStatus(), payment.getAmount(), providerPayment.providerPaymentId(),
                providerPayment.payUrl());
        String body = jsonMapper.writeValueAsString(response);
        idempotencyRepository.complete(customerId, key, HttpStatus.CREATED.value(), body);
        return new StoredResponse(HttpStatus.CREATED.value(), body);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
