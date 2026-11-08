package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class HoldExpiryServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private SeatHoldRepository seatHoldRepository;
    @Mock
    private ApplicationEventPublisher events;

    private HoldExpiryService service;

    @BeforeEach
    void setUp() {
        service = new HoldExpiryService(orderRepository, seatHoldRepository, events,
                new HoldProperties(Duration.ofMinutes(10), 6, Duration.ofSeconds(30), 100), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Order pendingOrder(long id) {
        Order order = Order.pending(7L, 3L, NOW.minusSeconds(1));
        ReflectionTestUtils.setField(order, "id", id);
        return order;
    }

    @Test
    void expiresTheClaimedOrdersAndReleasesTheirSeats() {
        Order first = pendingOrder(1L);
        Order second = pendingOrder(2L);
        when(orderRepository.claimExpired(NOW, 100)).thenReturn(List.of(first, second));
        when(seatHoldRepository.releaseSeats(List.of(1L, 2L))).thenReturn(Map.of(3L, List.of(10L, 11L, 12L)));

        int expired = service.expireBatch();

        assertThat(expired).isEqualTo(2);
        assertThat(first.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(second.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void publishesOneEventPerEventWithTheReleasedSeats() {
        when(orderRepository.claimExpired(NOW, 100)).thenReturn(List.of(pendingOrder(1L)));
        Map<Long, List<Long>> released = new LinkedHashMap<>();
        released.put(3L, List.of(10L, 11L));
        released.put(4L, List.of(20L));
        when(seatHoldRepository.releaseSeats(List.of(1L))).thenReturn(released);

        service.expireBatch();

        ArgumentCaptor<SeatsReleased> published = ArgumentCaptor.forClass(SeatsReleased.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues()).containsExactly(
                new SeatsReleased(3L, List.of(10L, 11L)), new SeatsReleased(4L, List.of(20L)));
    }

    @Test
    void doesNothingWhenNoHoldHasRunOut() {
        when(orderRepository.claimExpired(NOW, 100)).thenReturn(List.of());

        assertThat(service.expireBatch()).isZero();

        verifyNoInteractions(seatHoldRepository, events);
    }

    @Test
    void publishesNothingWhenNoSeatWasStillHeld() {
        when(orderRepository.claimExpired(NOW, 100)).thenReturn(List.of(pendingOrder(1L)));
        when(seatHoldRepository.releaseSeats(any())).thenReturn(Map.of());

        assertThat(service.expireBatch()).isEqualTo(1);

        verify(events, never()).publishEvent(any(Object.class));
    }
}
