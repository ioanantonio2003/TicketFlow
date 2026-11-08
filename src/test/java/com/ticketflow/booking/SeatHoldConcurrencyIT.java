package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import com.ticketflow.booking.dto.CreateOrderRequest;
import com.ticketflow.common.exception.BusinessRuleException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The proof of CLAUDE.md section 7 and ADR-0001: many threads hold seats at the same moment through the
 * real service and the real database, and no seat ever ends up in two orders.
 *
 * <p>Every threads first waits on a latch and is released together, to make the overlap as large as possible.
 * Each test creates its own event and customers, so tests are independent and can run in any order.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SeatHoldConcurrencyIT {

    private static final int SEATS = 100;
    private static final int THREADS = 200;

    @Autowired
    private OrderService orderService;
    @Autowired
    private JdbcTemplate jdbc;

    /** Result of one attempt: the seats it asked for and whether it won. */
    private record Attempt(List<Long> seats, boolean succeeded) {
    }

    @RepeatedTest(5)
    void twoHundredCustomersGrabRandomSeatsOfOneHundredAndNoSeatIsHeldTwice() throws Exception {
        BookingTestData data = new BookingTestData(jdbc);
        PublishedEvent event = data.publishedEvent(SEATS, "20.00");
        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long customer = data.user("CUSTOMER");
            Long seat = event.seatIds().get(ThreadLocalRandom.current().nextInt(SEATS));
            tasks.add(() -> hold(customer, event.eventId(), List.of(seat)));
        }

        List<Attempt> attempts = runTogether(tasks);

        long wins = attempts.stream().filter(Attempt::succeeded).count();
        // seats asked for by at least one thread: each of them must have exactly one winner
        long distinctSeatsAskedFor = attempts.stream().flatMap(a -> a.seats().stream()).distinct().count();
        assertThat(wins).isEqualTo(distinctSeatsAskedFor).isBetween(1L, (long) SEATS);
        assertInvariants(data, event, (int) wins, 1);
    }

    @Test
    void everybodyWantsTheSameSeatAndExactlyOneWins() throws Exception {
        BookingTestData data = new BookingTestData(jdbc);
        PublishedEvent event = data.publishedEvent(5, "20.00");
        Long seat = event.seatIds().get(0);
        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long customer = data.user("CUSTOMER");
            tasks.add(() -> hold(customer, event.eventId(), List.of(seat)));
        }

        List<Attempt> attempts = runTogether(tasks);

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertInvariants(data, event, 1, 1);
    }

    @RepeatedTest(3)
    void requestsForSeveralSeatsAreAllOrNothing() throws Exception {
        BookingTestData data = new BookingTestData(jdbc);
        PublishedEvent event = data.publishedEvent(40, "20.00");
        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long customer = data.user("CUSTOMER");
            List<Long> shuffled = new ArrayList<>(event.seatIds());
            Collections.shuffle(shuffled, ThreadLocalRandom.current());
            List<Long> three = List.copyOf(shuffled.subList(0, 3)); // overlapping triples compete for seats
            tasks.add(() -> hold(customer, event.eventId(), three));
        }

        List<Attempt> attempts = runTogether(tasks);

        int wins = (int) attempts.stream().filter(Attempt::succeeded).count();
        assertThat(wins).isBetween(1, 13); // 40 seats / 3 per request: at most 13 requests can win
        // a request that lost holds none of its seats: only winners' seats are held
        assertInvariants(data, event, wins, 3);
        assertThat(data.count("select count(*) from event_seats where event_id = ? and status = 'HELD'",
                event.eventId())).isEqualTo(wins * 3);
    }

    @RepeatedTest(3)
    void oneCustomerFiringParallelRequestsNeverExceedsTheLimit() throws Exception {
        BookingTestData data = new BookingTestData(jdbc);
        PublishedEvent event = data.publishedEvent(30, "20.00");
        long customer = data.user("CUSTOMER");
        // three requests of 4 different seats each; two of them together would be 8 > 6
        List<Callable<Attempt>> tasks = List.of(
                () -> hold(customer, event.eventId(), event.seatIds().subList(0, 4)),
                () -> hold(customer, event.eventId(), event.seatIds().subList(4, 8)),
                () -> hold(customer, event.eventId(), event.seatIds().subList(8, 12)));

        List<Attempt> attempts = runTogether(tasks);

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(1);
        assertThat(data.count("select count(*) from event_seats where event_id = ? and status = 'HELD'",
                event.eventId())).isEqualTo(4);
        assertThat(data.count("select count(*) from orders where customer_id = ?", customer)).isEqualTo(1);
    }

    @Test
    void oneCustomerCanReachExactlyTheLimitWithParallelSmallRequests() throws Exception {
        BookingTestData data = new BookingTestData(jdbc);
        PublishedEvent event = data.publishedEvent(30, "20.00");
        long customer = data.user("CUSTOMER");
        // eight requests of 1 seat each: exactly 6 may win, whatever the interleaving
        List<Callable<Attempt>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<Long> seat = List.of(event.seatIds().get(i));
            tasks.add(() -> hold(customer, event.eventId(), seat));
        }

        List<Attempt> attempts = runTogether(tasks);

        assertThat(attempts.stream().filter(Attempt::succeeded)).hasSize(6);
        assertThat(data.count("select count(*) from event_seats where event_id = ? and status = 'HELD'",
                event.eventId())).isEqualTo(6);
    }

    // ---- helpers ----

    /** Runs the service call; a lost race (409) or a refused limit (422) is a normal outcome, anything else fails. */
    private Attempt hold(long customerId, long eventId, List<Long> seats) {
        try {
            orderService.create(customerId, new CreateOrderRequest(eventId, seats));
            return new Attempt(seats, true);
        } catch (SeatsUnavailableException | BusinessRuleException expected) {
            return new Attempt(seats, false);
        }
    }

    /** Starts all tasks on virtual threads, releases them at the same instant and waits for all of them. */
    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    started.incrementAndGet();
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS)); // rethrows any unexpected exception
            }
            return results;
        }
    }

    /**
     * The invariants that must hold after any amount of contention: seats held == seats in orders, no seat in
     * two orders, every held seat belongs to an order that lists it, no order left over from a lost request.
     */
    private void assertInvariants(BookingTestData data, PublishedEvent event, int winningRequests, int seatsPerRequest) {
        long id = event.eventId();
        int held = data.count("select count(*) from event_seats where event_id = ? and status = 'HELD'", id);
        int items = data.count("select count(*) from order_items oi join orders o on o.id = oi.order_id "
                + "where o.event_id = ?", id);
        int distinctSeatsInItems = data.count("select count(distinct oi.event_seat_id) from order_items oi "
                + "join orders o on o.id = oi.order_id where o.event_id = ?", id);

        assertThat(items).as("order items = successful requests x seats").isEqualTo(winningRequests * seatsPerRequest);
        assertThat(held).as("held seats = items").isEqualTo(items);
        assertThat(distinctSeatsInItems).as("no seat in two orders").isEqualTo(items);
        assertThat(data.count("select count(*) from event_seats es where es.event_id = ? and es.status = 'HELD' "
                + "and not exists (select 1 from order_items oi where oi.order_id = es.order_id "
                + "and oi.event_seat_id = es.id)", id)).as("every held seat is listed by its order").isZero();
        assertThat(data.count("select count(*) from event_seats where event_id = ? and status <> 'HELD' "
                + "and order_id is not null", id)).as("only held seats point to an order").isZero();
        assertThat(data.count("select count(*) from orders where event_id = ?", id))
                .as("lost requests leave no order behind").isEqualTo(winningRequests);
    }
}
