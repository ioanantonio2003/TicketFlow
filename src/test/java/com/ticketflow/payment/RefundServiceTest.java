package com.ticketflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.booking.CancellationReason;
import com.ticketflow.booking.OrderRefunds;
import com.ticketflow.booking.RefundableOrder;
import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ExternalServiceException;
import com.ticketflow.payment.dto.RefundResponse;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    private static final Long CUSTOMER = 7L;
    private static final Long ORDER = 55L;

    @Mock
    private OrderRefunds orderRefunds;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentGateway gateway;

    private RefundService service;
    private Payment payment;

    @BeforeEach
    void setUp() {
        service = new RefundService(orderRefunds, paymentRepository, gateway, mock(PlatformTransactionManager.class));
        payment = Payment.pending(ORDER, new BigDecimal("75.00"));
        ReflectionTestUtils.setField(payment, "id", 3L);
        payment.attachProvider("fp_1");
        payment.succeed();
    }

    private void paymentExists() {
        when(paymentRepository.findFirstByOrderIdAndStatus(ORDER, PaymentStatus.SUCCEEDED))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.findById(3L)).thenReturn(Optional.of(payment));
    }

    @Test
    void refundsTheProviderFirstAndThenChangesOurState() {
        when(orderRefunds.requireRefundable(CUSTOMER, ORDER))
                .thenReturn(new RefundableOrder(ORDER, 3L, new BigDecimal("75.00")));
        paymentExists();

        RefundResponse response = service.refundMyOrder(CUSTOMER, ORDER);

        assertThat(response).isEqualTo(new RefundResponse(ORDER, new BigDecimal("75.00"), "REFUNDED"));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        InOrder order = inOrder(orderRefunds, gateway);
        order.verify(orderRefunds).requireRefundable(CUSTOMER, ORDER);
        order.verify(gateway).refund("fp_1");
        order.verify(orderRefunds).refund(ORDER, CancellationReason.CUSTOMER_REQUEST);
    }

    @Test
    void whenTheProviderFailsNothingChangesAndTheCustomerCanRetry() {
        when(orderRefunds.requireRefundable(CUSTOMER, ORDER))
                .thenReturn(new RefundableOrder(ORDER, 3L, new BigDecimal("75.00")));
        when(paymentRepository.findFirstByOrderIdAndStatus(ORDER, PaymentStatus.SUCCEEDED))
                .thenReturn(Optional.of(payment));
        doThrow(new ExternalServiceException("down")).when(gateway).refund("fp_1");

        assertThatThrownBy(() -> service.refundMyOrder(CUSTOMER, ORDER)).isInstanceOf(ExternalServiceException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(orderRefunds, never()).refund(any(), any());
    }

    @Test
    void theRulesAreCheckedBeforeAnyMoneyMoves() {
        when(orderRefunds.requireRefundable(CUSTOMER, ORDER))
                .thenThrow(new BusinessRuleException("Refunds are possible until 24 hours before the event starts."));

        assertThatThrownBy(() -> service.refundMyOrder(CUSTOMER, ORDER)).isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(gateway, paymentRepository);
    }

    @Test
    void anOrderWithoutASucceededPaymentCannotBeRefunded() {
        when(orderRefunds.requireRefundable(CUSTOMER, ORDER))
                .thenReturn(new RefundableOrder(ORDER, 3L, new BigDecimal("75.00")));
        when(paymentRepository.findFirstByOrderIdAndStatus(ORDER, PaymentStatus.SUCCEEDED))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refundMyOrder(CUSTOMER, ORDER)).isInstanceOf(ConflictException.class);

        verifyNoInteractions(gateway);
    }

    @Test
    void eventCancellationRefundsWithoutTheDeadlineCheck() {
        paymentExists();

        BigDecimal amount = service.refundBecauseEventWasCancelled(ORDER);

        assertThat(amount).isEqualByComparingTo("75.00");
        verify(orderRefunds).refund(ORDER, CancellationReason.EVENT_CANCELLED);
        verify(orderRefunds, never()).requireRefundable(any(), any());
    }
}
