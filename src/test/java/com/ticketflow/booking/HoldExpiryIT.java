package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.MutableClock;
import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import com.ticketflow.booking.dto.CreateOrderRequest;
import com.ticketflow.booking.dto.OrderResponse;
import com.ticketflow.common.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Holds run out when the (movable) clock passes their deadline, the job releases them, and running it again
 * changes nothing. No sleeping: the test moves the clock and calls the job directly.
 */
@SpringBootTest(properties = "app.booking.expiry-batch-size=2")
@Import({TestcontainersConfig.class, HoldExpiryIT.TestBeans.class})
@ActiveProfiles("test")
class HoldExpiryIT {

    /** Remembers every SeatsReleased that reached an AFTER_COMMIT listener, like a real consumer would. */
    static class ReleasedEvents {
        private final List<SeatsReleased> received = new ArrayList<>();

        @TransactionalEventListener
        synchronized void on(SeatsReleased event) {
            received.add(event);
        }

        synchronized List<SeatsReleased> forEvent(long eventId) {
            return received.stream().filter(e -> e.eventId() == eventId).toList();
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.now());
        }

        @Bean
        ReleasedEvents releasedEvents() {
            return new ReleasedEvents();
        }
    }

    @Autowired
    private OrderService orderService;
    @Autowired
    private HoldExpiryService expiryService;
    @Autowired
    private HoldExpiryJob expiryJob;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ReleasedEvents released;
    @Autowired
    private JdbcTemplate jdbc;

    private BookingTestData data() {
        return new BookingTestData(jdbc);
    }

    private OrderResponse hold(long customer, PublishedEvent event, int... positions) {
        List<Long> ids = new ArrayList<>();
        for (int position : positions) {
            ids.add(event.seatIds().get(position));
        }
        return orderService.create(customer, new CreateOrderRequest(event.eventId(), ids));
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int seatsWithStatus(PublishedEvent event, String status) {
        return data().count("select count(*) from event_seats where event_id = ? and status = ?",
                event.eventId(), status);
    }

    @Test
    void aHoldIsReleasedOnlyAfterItsDeadline() {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        long orderId = hold(data().user("CUSTOMER"), event, 0, 1).id();

        clock.advance(Duration.ofMinutes(9));
        expiryService.expireBatch();
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");     // 9 of 10 minutes
        assertThat(seatsWithStatus(event, "HELD")).isEqualTo(2);

        clock.advance(Duration.ofMinutes(2));
        expiryService.expireBatch();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");     // 11 of 10 minutes
        assertThat(seatsWithStatus(event, "HELD")).isZero();
        assertThat(seatsWithStatus(event, "AVAILABLE")).isEqualTo(5);
        assertThat(data().count("select count(*) from event_seats where event_id = ? and order_id is not null",
                event.eventId())).isZero();
    }

    @Test
    void runningTheJobTwiceChangesNothingTheSecondTime() {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        long orderId = hold(data().user("CUSTOMER"), event, 0, 1, 2).id();
        clock.advance(Duration.ofMinutes(11));

        expiryJob.releaseExpiredHolds();
        long versionAfterFirstRun = jdbc.queryForObject("select version from orders where id = ?", Long.class, orderId);
        List<Long> seatVersions = jdbc.queryForList(
                "select version from event_seats where event_id = ? order by id", Long.class, event.eventId());
        expiryJob.releaseExpiredHolds();

        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select version from orders where id = ?", Long.class, orderId))
                .isEqualTo(versionAfterFirstRun);
        assertThat(jdbc.queryForList("select version from event_seats where event_id = ? order by id", Long.class,
                event.eventId())).isEqualTo(seatVersions);
        assertThat(released.forEvent(event.eventId())).hasSize(1);   // announced once, not twice
    }

    @Test
    void theReleaseIsAnnouncedWithTheSeatsOfTheOrder() {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        hold(data().user("CUSTOMER"), event, 1, 3);
        clock.advance(Duration.ofMinutes(11));

        expiryService.expireBatch();

        assertThat(released.forEvent(event.eventId())).singleElement().satisfies(e ->
                assertThat(e.seatIds()).containsExactlyInAnyOrder(event.seatIds().get(1), event.seatIds().get(3)));
    }

    @Test
    void onlyTheOrdersThatRanOutAreReleased() {
        PublishedEvent event = data().publishedEvent(6, "10.00");
        long early = hold(data().user("CUSTOMER"), event, 0).id();
        clock.advance(Duration.ofMinutes(8));
        long late = hold(data().user("CUSTOMER"), event, 1).id();
        clock.advance(Duration.ofMinutes(3));  // early: 11 min old, late: 3 min old

        expiryJob.releaseExpiredHolds();

        assertThat(orderStatus(early)).isEqualTo("EXPIRED");
        assertThat(orderStatus(late)).isEqualTo("PENDING");
        assertThat(seatsWithStatus(event, "HELD")).isEqualTo(1);
    }

    @Test
    void paidAndCancelledOrdersAreNotTouched() {
        PublishedEvent event = data().publishedEvent(6, "10.00");
        long customer = data().user("CUSTOMER");
        long cancelled = hold(customer, event, 0).id();
        orderService.cancel(customer, cancelled);
        // a paid order, set up directly: payment itself comes in a later step
        long paid = hold(data().user("CUSTOMER"), event, 1).id();
        jdbc.update("update orders set status = 'PAID' where id = ?", paid);
        jdbc.update("update event_seats set status = 'SOLD' where order_id = ?", paid);
        clock.advance(Duration.ofHours(1));

        expiryJob.releaseExpiredHolds();

        assertThat(orderStatus(cancelled)).isEqualTo("CANCELLED");
        assertThat(orderStatus(paid)).isEqualTo("PAID");
        assertThat(seatsWithStatus(event, "SOLD")).isEqualTo(1);
    }

    @Test
    void theJobWorksThroughSeveralBatches() {
        PublishedEvent event = data().publishedEvent(10, "10.00");
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 5; i++) {   // the batch size in this test is 2
            orders.add(hold(data().user("CUSTOMER"), event, i).id());
        }
        clock.advance(Duration.ofMinutes(11));

        expiryJob.releaseExpiredHolds();

        assertThat(orders).allSatisfy(id -> assertThat(orderStatus(id)).isEqualTo("EXPIRED"));
        assertThat(seatsWithStatus(event, "AVAILABLE")).isEqualTo(10);
    }

    @Test
    void anExpiredSeatCanBeBookedByAnotherCustomerAndTheFirstCustomerKeepsNothing() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        long first = data().user("CUSTOMER");
        long firstOrder = hold(first, event, 0).id();
        clock.advance(Duration.ofMinutes(11));
        expiryJob.releaseExpiredHolds();

        long secondOrder = hold(data().user("CUSTOMER"), event, 0).id();

        assertThat(orderStatus(firstOrder)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select order_id from event_seats where id = ?", Long.class,
                event.seatIds().get(0))).isEqualTo(secondOrder);
        // the first customer's seat does not count against their limit any more
        assertThat(hold(first, event, 1).status()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void cancellingAnExpiredButNotYetReleasedOrderIsStillAllowed() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        long customer = data().user("CUSTOMER");
        long orderId = hold(customer, event, 0).id();
        clock.advance(Duration.ofMinutes(11));   // the deadline passed but the job has not run yet

        orderService.cancel(customer, orderId);

        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(seatsWithStatus(event, "AVAILABLE")).isEqualTo(3);
    }

    @Test
    void cancellingAnExpiredOrderIsRefused() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        long customer = data().user("CUSTOMER");
        long orderId = hold(customer, event, 0).id();
        clock.advance(Duration.ofMinutes(11));
        expiryJob.releaseExpiredHolds();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.cancel(customer, orderId))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelAnnouncesTheReleasedSeats() {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        long customer = data().user("CUSTOMER");
        long orderId = hold(customer, event, 0, 2).id();

        orderService.cancel(customer, orderId);

        assertThat(released.forEvent(event.eventId())).singleElement().satisfies(e ->
                assertThat(e.seatIds()).containsExactlyInAnyOrder(event.seatIds().get(0), event.seatIds().get(2)));
    }

    /**
     * Cancel and the job race for the same order. Whoever gets the row lock first wins; the other must
     * either skip the row (job) or be refused (cancel). The seats are released exactly once.
     */
    @RepeatedTest(10)
    void cancelAndExpiryRacingForTheSameOrderReleaseTheSeatsExactlyOnce() throws Exception {
        PublishedEvent event = data().publishedEvent(3, "10.00");
        long customer = data().user("CUSTOMER");
        long orderId = hold(customer, event, 0, 1).id();
        clock.advance(Duration.ofMinutes(11));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger cancelSucceeded = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> cancel = pool.submit(() -> {
                start.await();
                try {
                    orderService.cancel(customer, orderId);
                    cancelSucceeded.incrementAndGet();
                } catch (ConflictException lost) {
                    // the job expired the order first
                }
                return null;
            });
            Future<?> expire = pool.submit(() -> {
                start.await();
                expiryService.expireBatch();
                return null;
            });
            start.countDown();
            cancel.get(30, TimeUnit.SECONDS);
            expire.get(30, TimeUnit.SECONDS);
        }

        assertThat(orderStatus(orderId)).isIn("CANCELLED", "EXPIRED");
        assertThat(orderStatus(orderId)).isEqualTo(cancelSucceeded.get() == 1 ? "CANCELLED" : "EXPIRED");
        assertThat(seatsWithStatus(event, "AVAILABLE")).isEqualTo(3);
        assertThat(released.forEvent(event.eventId())).hasSize(1);
    }
}
