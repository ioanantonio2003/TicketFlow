package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The conditional UPDATE and the queries around it, against a real PostgreSQL. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, SeatHoldRepository.class})
@ActiveProfiles("test")
class SeatHoldRepositoryTest {

    private static final Instant EXPIRES = Instant.now().plus(Duration.ofMinutes(10));

    @Autowired
    private SeatHoldRepository seatHoldRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private BookingTestData data;
    private PublishedEvent event;
    private long customer;
    private long firstOrder;
    private long secondOrder;

    @BeforeEach
    void setUp() {
        data = new BookingTestData(jdbc);
        event = data.publishedEvent(10, "50.00");
        customer = data.user("CUSTOMER");
        firstOrder = data.order(customer, event.eventId(), "PENDING", EXPIRES);
        secondOrder = data.order(data.user("CUSTOMER"), event.eventId(), "PENDING", EXPIRES);
    }

    @Test
    void holdingAvailableSeatsReturnsTheNumberOfSeatsHeldAndMarksThem() {
        List<Long> seats = event.seatIds().subList(0, 3);

        int held = seatHoldRepository.holdSeats(event.eventId(), seats, firstOrder, EXPIRES);

        assertThat(held).isEqualTo(3);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status, order_id, hold_expires_at, version from event_seats where id in (?, ?, ?)",
                seats.get(0), seats.get(1), seats.get(2));
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("HELD");
            assertThat(row.get("order_id")).isEqualTo(firstOrder);
            assertThat(row.get("hold_expires_at")).isNotNull();
            assertThat(row.get("version")).isEqualTo(1L); // bumped
        });
        assertThat(data.count("select count(*) from event_seats where event_id = ? and status = 'AVAILABLE'",
                event.eventId())).isEqualTo(7); // the others are untouched
    }

    @Test
    void aHeldSeatCannotBeHeldAgain() {
        Long seat = event.seatIds().get(0);
        assertThat(seatHoldRepository.holdSeats(event.eventId(), List.of(seat), firstOrder, EXPIRES)).isEqualTo(1);

        int second = seatHoldRepository.holdSeats(event.eventId(), List.of(seat), secondOrder, EXPIRES);

        assertThat(second).isZero();
        assertThat(jdbc.queryForObject("select order_id from event_seats where id = ?", Long.class, seat))
                .isEqualTo(firstOrder); // still the first order's
    }

    @Test
    void aMixOfFreeAndTakenSeatsReturnsOnlyTheFreeOnesSoTheCallerCanDetectIt() {
        Long taken = event.seatIds().get(0);
        Long free = event.seatIds().get(1);
        seatHoldRepository.holdSeats(event.eventId(), List.of(taken), firstOrder, EXPIRES);

        int held = seatHoldRepository.holdSeats(event.eventId(), List.of(taken, free), secondOrder, EXPIRES);

        assertThat(held).isEqualTo(1); // 1 of 2 requested: the service turns this into a 409 and rolls back
    }

    @Test
    void soldSeatsAreNeverHeld() {
        Long seat = event.seatIds().get(0);
        jdbc.update("update event_seats set status = 'SOLD' where id = ?", seat);

        assertThat(seatHoldRepository.holdSeats(event.eventId(), List.of(seat), firstOrder, EXPIRES)).isZero();
        assertThat(jdbc.queryForObject("select status from event_seats where id = ?", String.class, seat))
                .isEqualTo("SOLD");
    }

    @Test
    void seatsOfAnotherEventAreNotTouched() {
        PublishedEvent other = data.publishedEvent(3, "10.00");

        // the seat ids are right, but they are asked for under the wrong event
        int held = seatHoldRepository.holdSeats(event.eventId(), other.seatIds(), firstOrder, EXPIRES);

        assertThat(held).isZero();
        assertThat(data.count("select count(*) from event_seats where event_id = ? and status = 'AVAILABLE'",
                other.eventId())).isEqualTo(3);
    }

    @Test
    void unknownSeatIdsHoldNothing() {
        assertThat(seatHoldRepository.holdSeats(event.eventId(), List.of(-1L, -2L), firstOrder, EXPIRES)).isZero();
    }

    @Test
    void pricesOfHeldSeatsComeFromThePriceCategory() {
        List<Long> seats = event.seatIds().subList(0, 2);
        seatHoldRepository.holdSeats(event.eventId(), seats, firstOrder, EXPIRES);

        Map<Long, BigDecimal> prices = seatHoldRepository.pricesOfHeldSeats(firstOrder);

        assertThat(prices.keySet()).containsExactlyElementsOf(seats);
        assertThat(prices.values()).allSatisfy(p -> assertThat(p).isEqualByComparingTo("50.00"));
        assertThat(seatHoldRepository.pricesOfHeldSeats(secondOrder)).isEmpty();
    }

    @Test
    void theAdvisoryLockCanBeTakenRepeatedlyInOneTransaction() {
        seatHoldRepository.lockCustomerForEvent(customer, event.eventId());
        seatHoldRepository.lockCustomerForEvent(customer, event.eventId()); // re-entrant for the same session
    }

    // ---- the per-customer limit counts only active seats ----

    @Test
    void activeSeatsCountPaidAndUnexpiredPendingOrdersOnly() {
        Instant now = Instant.now();
        long paid = data.order(customer, event.eventId(), "PAID", null);
        data.orderItem(paid, event.seatIds().get(0), "50.00");
        data.orderItem(paid, event.seatIds().get(1), "50.00");
        long pending = data.order(customer, event.eventId(), "PENDING", now.plus(Duration.ofMinutes(5)));
        data.orderItem(pending, event.seatIds().get(2), "50.00");
        long expiredHold = data.order(customer, event.eventId(), "PENDING", now.minus(Duration.ofMinutes(1)));
        data.orderItem(expiredHold, event.seatIds().get(3), "50.00");
        long expired = data.order(customer, event.eventId(), "EXPIRED", null);
        data.orderItem(expired, event.seatIds().get(4), "50.00");
        long cancelled = data.order(customer, event.eventId(), "CANCELLED", null);
        data.orderItem(cancelled, event.seatIds().get(5), "50.00");
        long refunded = data.order(customer, event.eventId(), "REFUNDED", null);
        data.orderItem(refunded, event.seatIds().get(6), "50.00");

        assertThat(orderItemRepository.countActiveSeats(customer, event.eventId(), now)).isEqualTo(3);
    }

    @Test
    void activeSeatsAreCountedPerCustomerAndPerEvent() {
        long mine = data.order(customer, event.eventId(), "PAID", null);
        data.orderItem(mine, event.seatIds().get(0), "50.00");
        PublishedEvent other = data.publishedEvent(2, "10.00");
        long inOtherEvent = data.order(customer, other.eventId(), "PAID", null);
        data.orderItem(inOtherEvent, other.seatIds().get(0), "10.00");
        long someoneElse = data.order(data.user("CUSTOMER"), event.eventId(), "PAID", null);
        data.orderItem(someoneElse, event.seatIds().get(1), "50.00");

        assertThat(orderItemRepository.countActiveSeats(customer, event.eventId(), Instant.now())).isEqualTo(1);
        assertThat(orderItemRepository.countActiveSeats(customer, other.eventId(), Instant.now())).isEqualTo(1);
    }
}
