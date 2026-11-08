package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.booking.dto.CreateOrderRequest;
import com.ticketflow.booking.dto.OrderResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Long CUSTOMER = 7L;
    private static final Long EVENT = 3L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private OrderReadRepository orderReadRepository;
    @Mock
    private EventCatalog eventCatalog;
    @Mock
    private ApplicationEventPublisher events;

    private OrderService service;

    @BeforeEach
    void setUp() {
        service = new OrderService(orderRepository, orderItemRepository, seatHoldRepository, orderReadRepository,
                eventCatalog, new HoldProperties(Duration.ofMinutes(10), 6, Duration.ofSeconds(30), 100), Clock.fixed(NOW, ZoneOffset.UTC),
                events);
    }

    private void eventIsBookable() {
        when(eventCatalog.requireBookable(EVENT, NOW)).thenReturn(new EventRef(EVENT, "Concert", NOW.plusSeconds(9999)));
    }

    private void orderGetsId(Long id) {
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> {
            Order order = inv.getArgument(0);
            ReflectionTestUtils.setField(order, "id", id);
            return order;
        });
    }

    @Test
    void holdsSeatsAndPricesTheOrder() {
        eventIsBookable();
        orderGetsId(55L);
        when(orderItemRepository.countActiveSeats(CUSTOMER, EVENT, NOW)).thenReturn(0L);
        when(seatHoldRepository.holdSeats(eq(EVENT), eq(List.of(10L, 11L)), eq(55L), any())).thenReturn(2);
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        prices.put(10L, new BigDecimal("50.00"));
        prices.put(11L, new BigDecimal("25.50"));
        when(seatHoldRepository.pricesOfHeldSeats(55L)).thenReturn(prices);
        when(orderReadRepository.itemsByOrder(List.of(55L))).thenReturn(Map.of());

        OrderResponse response = service.create(CUSTOMER, new CreateOrderRequest(EVENT, List.of(10L, 11L)));

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.totalAmount()).isEqualByComparingTo("75.50");
        assertThat(response.holdExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        ArgumentCaptor<OrderItem> items = ArgumentCaptor.forClass(OrderItem.class);
        verify(orderItemRepository, org.mockito.Mockito.times(2)).save(items.capture());
        assertThat(items.getAllValues()).extracting(OrderItem::getEventSeatId).containsExactly(10L, 11L);
        assertThat(items.getAllValues()).extracting(OrderItem::getPrice)
                .usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .containsExactly(new BigDecimal("50.00"), new BigDecimal("25.50"));
    }

    @Test
    void ordersBelongToTheCallerAndStartPending() {
        eventIsBookable();
        orderGetsId(55L);
        when(seatHoldRepository.holdSeats(any(), any(), any(), any())).thenReturn(1);
        when(seatHoldRepository.pricesOfHeldSeats(55L)).thenReturn(Map.of(10L, new BigDecimal("1.00")));

        service.create(CUSTOMER, new CreateOrderRequest(EVENT, List.of(10L)));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getCustomerId()).isEqualTo(CUSTOMER);
        assertThat(saved.getValue().getEventId()).isEqualTo(EVENT);
        assertThat(saved.getValue().getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void ifAnySeatIsUnavailableThePartialResultIsNotKept() {
        eventIsBookable();
        orderGetsId(55L);
        when(seatHoldRepository.holdSeats(any(), any(), any(), any())).thenReturn(1); // 1 of 2 seats

        assertThatThrownBy(() -> service.create(CUSTOMER, new CreateOrderRequest(EVENT, List.of(10L, 11L))))
                .isInstanceOf(SeatsUnavailableException.class)
                .hasMessage("One or more of the selected seats are no longer available.");

        verify(orderItemRepository, never()).save(any()); // the exception also rolls the transaction back
    }

    @Test
    void theLockIsTakenBeforeTheLimitIsCheckedAndBeforeAnythingIsWritten() {
        eventIsBookable();
        orderGetsId(55L);
        when(seatHoldRepository.holdSeats(any(), any(), any(), any())).thenReturn(1);
        when(seatHoldRepository.pricesOfHeldSeats(55L)).thenReturn(Map.of(10L, new BigDecimal("1.00")));

        service.create(CUSTOMER, new CreateOrderRequest(EVENT, List.of(10L)));

        InOrder order = inOrder(eventCatalog, seatHoldRepository, orderItemRepository, orderRepository);
        order.verify(eventCatalog).requireBookable(EVENT, NOW);
        order.verify(seatHoldRepository).lockCustomerForEvent(CUSTOMER, EVENT);
        order.verify(orderItemRepository).countActiveSeats(CUSTOMER, EVENT, NOW);
        order.verify(orderRepository).save(any(Order.class));
        order.verify(seatHoldRepository).holdSeats(eq(EVENT), any(), eq(55L), any());
    }

    @Test
    void moreThanTheMaximumPerRequestIsRejectedBeforeAnyQuery() {
        List<Long> seven = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);

        assertThatThrownBy(() -> service.create(CUSTOMER, new CreateOrderRequest(EVENT, seven)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("6");
        verifyNoInteractions(eventCatalog, seatHoldRepository, orderRepository);
    }

    @Test
    void theSameSeatTwiceIsRejected() {
        assertThatThrownBy(() -> service.create(CUSTOMER, new CreateOrderRequest(EVENT, List.of(1L, 1L))))
                .isInstanceOf(BusinessRuleException.class);
        verifyNoInteractions(eventCatalog, seatHoldRepository, orderRepository);
    }

    @Test
    void theLimitPerEventCountsSeatsTheCustomerAlreadyHas() {
        eventIsBookable();
        when(orderItemRepository.countActiveSeats(CUSTOMER, EVENT, NOW)).thenReturn(4L);

        assertThatThrownBy(() -> service.create(CUSTOMER, new CreateOrderRequest(EVENT, List.of(1L, 2L, 3L))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("already have 4");

        verify(orderRepository, never()).save(any());
        verify(seatHoldRepository, never()).holdSeats(any(), any(), anyLong(), any());
    }

    @Test
    void reachingExactlyTheLimitIsAllowed() {
        eventIsBookable();
        orderGetsId(55L);
        when(orderItemRepository.countActiveSeats(CUSTOMER, EVENT, NOW)).thenReturn(4L);
        when(seatHoldRepository.holdSeats(any(), any(), any(), any())).thenReturn(2);
        when(seatHoldRepository.pricesOfHeldSeats(55L)).thenReturn(
                Map.of(1L, new BigDecimal("1.00"), 2L, new BigDecimal("1.00")));

        OrderResponse response = service.create(CUSTOMER, new CreateOrderRequest(EVENT, List.of(1L, 2L)));

        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void anEventThatIsNotBookableStopsEverything() {
        when(eventCatalog.requireBookable(EVENT, NOW)).thenThrow(new ResourceNotFoundException("Event not found."));

        assertThatThrownBy(() -> service.create(CUSTOMER, new CreateOrderRequest(EVENT, List.of(1L))))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(seatHoldRepository, orderRepository);
    }

    @Test
    void someoneElsesOrderIsNotFound() {
        when(orderRepository.findByIdAndCustomerId(9L, CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(CUSTOMER, 9L)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- cancel ----

    private Order pendingOrder(long id) {
        Order order = Order.pending(CUSTOMER, EVENT, NOW.plusSeconds(300));
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    @Test
    void cancellingReleasesTheSeatsAndAnnouncesThem() {
        Order order = pendingOrder(55L);
        when(orderRepository.findByIdAndCustomerIdForUpdate(55L, CUSTOMER)).thenReturn(Optional.of(order));
        when(seatHoldRepository.releaseSeats(List.of(55L))).thenReturn(Map.of(EVENT, List.of(10L, 11L)));

        OrderResponse response = service.cancel(CUSTOMER, 55L);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(events).publishEvent(new SeatsReleased(EVENT, List.of(10L, 11L)));
    }

    @Test
    void cancellingSomeoneElsesOrderIsNotFoundAndReleasesNothing() {
        when(orderRepository.findByIdAndCustomerIdForUpdate(55L, CUSTOMER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel(CUSTOMER, 55L)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(seatHoldRepository, events);
    }

    @Test
    void anOrderThatIsNotPendingCannotBeCancelledAndReleasesNothing() {
        Order order = pendingOrder(55L);
        order.expire();
        when(orderRepository.findByIdAndCustomerIdForUpdate(55L, CUSTOMER)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(CUSTOMER, 55L)).isInstanceOf(ConflictException.class);

        verifyNoInteractions(seatHoldRepository, events);
    }
}
