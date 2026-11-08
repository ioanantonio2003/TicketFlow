package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.common.exception.ConflictException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderTest {

    private static Order order(OrderStatus status) {
        Order order = Order.pending(7L, 3L, Instant.parse("2030-01-01T10:10:00Z"));
        ReflectionTestUtils.setField(order, "status", status);
        return order;
    }

    @Test
    void aPendingOrderCanBeCancelled() {
        Order order = order(OrderStatus.PENDING);
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void aPendingOrderCanExpire() {
        Order order = order(OrderStatus.PENDING);
        order.expire();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.EXPIRED);
    }

    @Test
    void nothingExceptPendingCanBeCancelledOrExpired() {
        for (OrderStatus status : OrderStatus.values()) {
            if (status == OrderStatus.PENDING) {
                continue;
            }
            assertThatThrownBy(() -> order(status).cancel()).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> order(status).expire()).isInstanceOf(ConflictException.class);
            Order order = order(status);
            try {
                order.cancel();
            } catch (ConflictException expected) {
                // the status must not change when the transition is refused
            }
            assertThat(order.getStatus()).isEqualTo(status);
        }
    }

    @Test
    void onlyAPaidOrderCanBeRefunded() {
        Order paid = order(OrderStatus.PAID);
        paid.refund();
        assertThat(paid.getStatus()).isEqualTo(OrderStatus.REFUNDED);

        for (OrderStatus status : OrderStatus.values()) {
            if (status != OrderStatus.PAID) {
                assertThatThrownBy(() -> order(status).refund()).isInstanceOf(ConflictException.class);
            }
        }
    }
}
