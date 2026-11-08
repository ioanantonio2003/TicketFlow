package com.ticketflow.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.booking.OrderPayments;
import com.ticketflow.booking.PayableOrder;
import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ExternalServiceException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.payment.IdempotencyRepository.StoredKey;
import com.ticketflow.payment.PaymentGateway.ProviderPayment;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    private static final Long CUSTOMER = 7L;
    private static final Long ORDER = 55L;
    private static final String KEY = "key-1";

    @Mock
    private OrderPayments orderPayments;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private IdempotencyRepository idempotencyRepository;
    @Mock
    private PaymentGateway gateway;

    private CheckoutService service;

    @BeforeEach
    void setUp() {
        // a TransactionTemplate over a mock manager just runs the callback: these tests are about the sequence
        service = new CheckoutService(orderPayments, paymentRepository, idempotencyRepository, gateway,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), JsonMapper.builder().build());
    }

    private Payment savedPayment() {
        Payment payment = Payment.pending(ORDER, new BigDecimal("30.00"));
        ReflectionTestUtils.setField(payment, "id", 3L);
        when(paymentRepository.save(any(Payment.class))).thenReturn(payment);
        when(paymentRepository.findById(3L)).thenReturn(Optional.of(payment));
        return payment;
    }

    private void freshKeyAndPayableOrder() {
        when(idempotencyRepository.tryStart(eq(CUSTOMER), eq(KEY), anyString())).thenReturn(true);
        when(orderPayments.requirePayable(CUSTOMER, ORDER))
                .thenReturn(new PayableOrder(ORDER, 1L, new BigDecimal("30.00")));
    }

    @Test
    void createsThePaymentCallsTheProviderAndStoresTheAnswerUnderTheKey() {
        freshKeyAndPayableOrder();
        Payment payment = savedPayment();
        when(gateway.createPayment(new BigDecimal("30.00"), "order-55"))
                .thenReturn(new ProviderPayment("fp_1", "http://pay/fp_1"));

        StoredResponse response = service.checkout(CUSTOMER, ORDER, KEY);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"providerPaymentId\":\"fp_1\"").contains("\"status\":\"PENDING\"");
        assertThat(payment.getProviderPaymentId()).isEqualTo("fp_1");
        verify(idempotencyRepository).complete(CUSTOMER, KEY, 201, response.body());
    }

    @Test
    void theSameKeyAgainReplaysTheStoredAnswerWithoutCallingTheProvider() {
        when(idempotencyRepository.tryStart(eq(CUSTOMER), eq(KEY), anyString())).thenReturn(false);
        // the hash of "the same request" is whatever the service computes; echo it back
        when(idempotencyRepository.find(CUSTOMER, KEY)).thenAnswer(inv -> Optional.of(
                new StoredKey(capturedHash(), 201, "{\"stored\":true}")));

        StoredResponse response = service.checkout(CUSTOMER, ORDER, KEY);

        assertThat(response).isEqualTo(new StoredResponse(201, "{\"stored\":true}"));
        verifyNoInteractions(gateway, orderPayments, paymentRepository);
    }

    private String capturedHash() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(idempotencyRepository).tryStart(eq(CUSTOMER), eq(KEY), captor.capture());
        return captor.getValue();
    }

    @Test
    void theSameKeyForADifferentRequestIsRejected() {
        when(idempotencyRepository.tryStart(eq(CUSTOMER), eq(KEY), anyString())).thenReturn(false);
        when(idempotencyRepository.find(CUSTOMER, KEY))
                .thenReturn(Optional.of(new StoredKey("hash-of-another-request", 201, "{}")));

        assertThatThrownBy(() -> service.checkout(CUSTOMER, ORDER, KEY)).isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void aKeyWhoseFirstRequestIsStillRunningIsAConflict() {
        when(idempotencyRepository.tryStart(eq(CUSTOMER), eq(KEY), anyString())).thenReturn(false);
        when(idempotencyRepository.find(CUSTOMER, KEY)).thenAnswer(inv -> Optional.of(
                new StoredKey(capturedHash(), null, null)));

        assertThatThrownBy(() -> service.checkout(CUSTOMER, ORDER, KEY)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void anOrderThatCannotBePaidStopsBeforeAnyPaymentExists() {
        when(idempotencyRepository.tryStart(eq(CUSTOMER), eq(KEY), anyString())).thenReturn(true);
        when(orderPayments.requirePayable(CUSTOMER, ORDER)).thenThrow(new ConflictException("The order can no longer be paid."));

        assertThatThrownBy(() -> service.checkout(CUSTOMER, ORDER, KEY)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(gateway, paymentRepository);
    }

    @Test
    void someoneElsesOrderIsNotFound() {
        when(idempotencyRepository.tryStart(eq(CUSTOMER), eq(KEY), anyString())).thenReturn(true);
        when(orderPayments.requirePayable(CUSTOMER, ORDER)).thenThrow(new ResourceNotFoundException("Order not found."));

        assertThatThrownBy(() -> service.checkout(CUSTOMER, ORDER, KEY)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void ifTheProviderFailsThePaymentFailsAndTheKeyIsFreedForARetry() {
        freshKeyAndPayableOrder();
        Payment payment = savedPayment();
        when(gateway.createPayment(any(), anyString())).thenThrow(new ExternalServiceException("down"));

        assertThatThrownBy(() -> service.checkout(CUSTOMER, ORDER, KEY)).isInstanceOf(ExternalServiceException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(idempotencyRepository).delete(CUSTOMER, KEY);
        verify(idempotencyRepository, never()).complete(any(), anyString(), anyInt(), anyString());
    }

    @Test
    void theKeyHeaderIsRequiredAndBounded() {
        assertThatThrownBy(() -> service.checkout(CUSTOMER, ORDER, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.checkout(CUSTOMER, ORDER, "  ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.checkout(CUSTOMER, ORDER, "x".repeat(101)))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(idempotencyRepository, gateway);
    }
}
