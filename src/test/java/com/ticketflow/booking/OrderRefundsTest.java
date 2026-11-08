package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.event.EventCatalog;
import com.ticketflow.event.dto.EventRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** The refund deadline rule, with a fixed clock, and the order side of a refund. */
@ExtendWith(MockitoExtension.class)
class OrderRefundsTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Long CUSTOMER = 7L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private EventCatalog eventCatalog;
    @Mock
    private ApplicationEventPublisher events;

    private OrderRefunds refunds;

    @BeforeEach
    void setUp() {
        refunds = new OrderRefunds(orderRepository, seatHoldRepository, eventCatalog,
                new HoldProperties(Duration.ofMinutes(10), 6, Duration.ofSeconds(30), 100, Duration.ofHours(24)), events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Order order(OrderStatus status) {
        Order order = Order.pending(CUSTOMER, 3L, NOW.plusSeconds(600));
        ReflectionTestUtils.setField(order, "id", 55L);
        ReflectionTestUtils.setField(order, "status", status);
        order.assignTotal(new BigDecimal("75.00"));
        return order;
    }

    private void eventStartsIn(Duration fromNow) {
        when(eventCatalog.get(3L)).thenReturn(new EventRef(3L, "Concert", NOW.plus(fromNow)));
    }

    // ---- the deadline ----

    @Test
    void aRefundIsAllowedWhenTheEventIsMoreThan24HoursAway() {
        when(orderRepository.findByIdAndCustomerId(55L, CUSTOMER)).thenReturn(Optional.of(order(OrderStatus.PAID)));
        eventStartsIn(Duration.ofHours(48));

        RefundableOrder refundable = refunds.requireRefundable(CUSTOMER, 55L);

        assertThat(refundable.orderId()).isEqualTo(55L);
        assertThat(refundable.amount()).isEqualByComparingTo("75.00");
    }

    @Test
    void exactlyAtTheDeadlineIsStillAllowed() {
        when(orderRepository.findByIdAndCustomerId(55L, CUSTOMER)).thenReturn(Optional.of(order(OrderStatus.PAID)));
        eventStartsIn(Duration.ofHours(24));

        assertThatCode(() -> refunds.requireRefundable(CUSTOMER, 55L)).doesNotThrowAnyException();
    }

    @Test
    void oneSecondAfterTheDeadlineIsRefused() {
        when(orderRepository.findByIdAndCustomerId(55L, CUSTOMER)).thenReturn(Optional.of(order(OrderStatus.PAID)));
        eventStartsIn(Duration.ofHours(24).minusSeconds(1));

        assertThatThrownBy(() -> refunds.requireRefundable(CUSTOMER, 55L)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("24 hours");
    }

    @Test
    void anEventThatAlreadyStartedCannotBeRefunded() {
        when(orderRepository.findByIdAndCustomerId(55L, CUSTOMER)).thenReturn(Optional.of(order(OrderStatus.PAID)));
        eventStartsIn(Duration.ofHours(-1));

        assertThatThrownBy(() -> refunds.requireRefundable(CUSTOMER, 55L)).isInstanceOf(BusinessRuleException.class);
    }

    // ---- who and what ----

    @Test
    void onlyPaidOrdersCanBeRefunded() {
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.PAID) {
                continue;
            }
            when(orderRepository.findByIdAndCustomerId(55L, CUSTOMER)).thenReturn(Optional.of(order(status)));
            assertThatThrownBy(() -> refunds.requireRefundable(CUSTOMER, 55L)).isInstanceOf(ConflictException.class);
        }
    }

    @Test
    void someoneElsesOrderIsNotFound() {
        when(orderRepository.findByIdAndCustomerId(55L, CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refunds.requireRefundable(CUSTOMER, 55L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- the refund itself ----

    @Test
    void refundingReopensTheSeatsAndAnnouncesBoth() {
        Order paid = order(OrderStatus.PAID);
        when(orderRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(paid));
        when(seatHoldRepository.releaseSoldSeats(55L)).thenReturn(Map.of(3L, List.of(10L, 11L)));

        refunds.refund(55L, CancellationReason.EVENT_CANCELLED);

        assertThat(paid.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(events).publishEvent(new SeatsReleased(3L, List.of(10L, 11L)));
        verify(events).publishEvent(new OrderRefunded(55L, CUSTOMER, 3L, new BigDecimal("75.00"),
                CancellationReason.EVENT_CANCELLED));
    }

    @Test
    void anOrderThatIsAlreadyRefundedIsNotRefundedTwice() {
        when(orderRepository.findByIdForUpdate(55L)).thenReturn(Optional.of(order(OrderStatus.REFUNDED)));

        assertThatThrownBy(() -> refunds.refund(55L, CancellationReason.CUSTOMER_REQUEST))
                .isInstanceOf(ConflictException.class);

        verify(seatHoldRepository, never()).releaseSoldSeats(any());
        verify(events, never()).publishEvent(any(Object.class));
    }
}
