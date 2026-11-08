package com.ticketflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.booking.OrderPayments;
import com.ticketflow.booking.SettleResult;
import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.payment.dto.WebhookPayload;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private OrderPayments orderPayments;

    private PaymentSettlement settlement;
    private Payment payment;

    @BeforeEach
    void setUp() {
        settlement = new PaymentSettlement(paymentRepository, orderPayments);
        payment = Payment.pending(9L, new BigDecimal("30.00"));
        payment.attachProvider("fp_1");
    }

    private void paymentExists() {
        when(paymentRepository.findByProviderPaymentIdForUpdate("fp_1")).thenReturn(Optional.of(payment));
    }

    @Test
    void aSuccessfulPaymentOfAPendingOrderSucceeds() {
        paymentExists();
        when(orderPayments.settle(9L)).thenReturn(SettleResult.PAID);

        settlement.apply(new WebhookPayload("fp_1", WebhookPayload.SUCCESS));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    }

    @Test
    void aSuccessfulPaymentThatCameTooLateIsMarkedForRefund() {
        paymentExists();
        when(orderPayments.settle(9L)).thenReturn(SettleResult.TOO_LATE);

        settlement.apply(new WebhookPayload("fp_1", WebhookPayload.SUCCESS));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void aFailedPaymentLeavesTheOrderAlone() {
        paymentExists();

        settlement.apply(new WebhookPayload("fp_1", WebhookPayload.FAILURE));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(orderPayments);
    }

    @Test
    void aDuplicateDeliveryChangesNothing() {
        paymentExists();
        payment.fail();

        settlement.apply(new WebhookPayload("fp_1", WebhookPayload.SUCCESS));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verifyNoInteractions(orderPayments);
    }

    @Test
    void anUnknownPaymentIsNotFound() {
        when(paymentRepository.findByProviderPaymentIdForUpdate("fp_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlement.apply(new WebhookPayload("fp_1", WebhookPayload.SUCCESS)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnknownOutcomeIsRejected() {
        paymentExists();

        assertThatThrownBy(() -> settlement.apply(new WebhookPayload("fp_1", "MAYBE")))
                .isInstanceOf(BadRequestException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }
}
