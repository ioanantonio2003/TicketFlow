package com.ticketflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import com.ticketflow.booking.OrderStatus;
import com.ticketflow.reporting.dto.EventTotals;
import com.ticketflow.reporting.dto.SectionStats;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The reporting queries against a real PostgreSQL with data whose totals are known exactly.
 *
 * <p>The event: section Floor (6 seats, price 10) and section Balcony (4 seats, price 30).
 * Orders: PAID 2 Floor seats (20), PAID 1 Balcony seat (30), PENDING 1 Floor seat held (10),
 * REFUNDED 1 Floor seat (10, seat available again), CANCELLED (no seats).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, ReportingRepository.class})
@ActiveProfiles("test")
class ReportingRepositoryTest {

    @Autowired
    private ReportingRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    private BookingTestData data;
    private PublishedEvent event;
    private long customer;

    @BeforeEach
    void setUp() {
        data = new BookingTestData(jdbc);
        event = data.publishedEvent(6, "10.00");   // Floor, 6 seats at 10
        addBalcony(event, 4, "30.00");
        customer = data.user("CUSTOMER");
        List<Long> floor = seatIdsOf(event, "Floor");
        List<Long> balcony = seatIdsOf(event, "Balcony");

        long paid1 = data.order(customer, event.eventId(), "PAID", null);
        take(paid1, floor.get(0), "10.00", "SOLD");
        take(paid1, floor.get(1), "10.00", "SOLD");
        long paid2 = data.order(customer, event.eventId(), "PAID", null);
        take(paid2, balcony.get(0), "30.00", "SOLD");
        long pending = data.order(customer, event.eventId(), "PENDING", Instant.now().plus(Duration.ofMinutes(5)));
        take(pending, floor.get(2), "10.00", "HELD");
        long refunded = data.order(customer, event.eventId(), "REFUNDED", null);
        data.orderItem(refunded, floor.get(3), "10.00");   // its seat went back to AVAILABLE
        jdbc.update("update orders set total_amount = 10 where id = ?", refunded);
        data.order(customer, event.eventId(), "CANCELLED", null);
    }

    /** A second section with its own price and its event seats. */
    private void addBalcony(PublishedEvent event, int seats, String price) {
        long section = jdbc.queryForObject(
                "insert into sections (venue_id, name, sort_order) values (?, 'Balcony', 1) returning id", Long.class,
                event.venueId());
        jdbc.update("insert into seats (section_id, row_label, seat_number) "
                + "select ?, 'B', n from generate_series(1, ?) n", section, seats);
        long category = jdbc.queryForObject(
                "insert into price_categories (event_id, section_id, price) values (?, ?, ?) returning id", Long.class,
                event.eventId(), section, new BigDecimal(price));
        jdbc.update("insert into event_seats (event_id, seat_id, price_category_id, status, version) "
                + "select ?, s.id, ?, 'AVAILABLE', 0 from seats s where s.section_id = ?",
                event.eventId(), category, section);
    }

    private List<Long> seatIdsOf(PublishedEvent event, String section) {
        return jdbc.queryForList("select es.id from event_seats es join seats s on s.id = es.seat_id "
                + "join sections sec on sec.id = s.section_id where es.event_id = ? and sec.name = ? order by es.id",
                Long.class, event.eventId(), section);
    }

    /** Puts a seat into an order the way booking does: item, seat state, order total. */
    private void take(long orderId, long eventSeatId, String price, String seatStatus) {
        data.orderItem(orderId, eventSeatId, price);
        jdbc.update("update event_seats set status = ?, order_id = ? where id = ?", seatStatus, orderId, eventSeatId);
        jdbc.update("update orders set total_amount = total_amount + ? where id = ?", new BigDecimal(price), orderId);
    }

    // ---- one event ----

    @Test
    void sectionsHaveExactSeatCountsAndRevenueOfPaidOrdersOnly() {
        List<SectionStats> sections = repository.sections(event.eventId());

        assertThat(sections).extracting(SectionStats::name).containsExactly("Floor", "Balcony");
        SectionStats floor = sections.get(0);
        assertThat(floor.seats().total()).isEqualTo(6);
        assertThat(floor.seats().sold()).isEqualTo(2);
        assertThat(floor.seats().held()).isEqualTo(1);
        assertThat(floor.seats().available()).isEqualTo(3);
        assertThat(floor.revenue()).isEqualByComparingTo("20.00");   // the pending and refunded orders are not revenue
        SectionStats balcony = sections.get(1);
        assertThat(balcony.seats().total()).isEqualTo(4);
        assertThat(balcony.seats().sold()).isEqualTo(1);
        assertThat(balcony.seats().held()).isZero();
        assertThat(balcony.seats().available()).isEqualTo(3);
        assertThat(balcony.revenue()).isEqualByComparingTo("30.00");
    }

    @Test
    void ordersAreCountedByStatusAndRevenueIsTheMoneyOfPaidOnes() {
        ReportingRepository.OrderFigures figures = repository.orders(event.eventId());

        assertThat(figures.byStatus().get(OrderStatus.PAID)).isEqualTo(2);
        assertThat(figures.byStatus().get(OrderStatus.PENDING)).isEqualTo(1);
        assertThat(figures.byStatus().get(OrderStatus.REFUNDED)).isEqualTo(1);
        assertThat(figures.byStatus().get(OrderStatus.CANCELLED)).isEqualTo(1);
        assertThat(figures.byStatus().get(OrderStatus.EXPIRED)).isZero();   // every status is present
        assertThat(figures.revenue()).isEqualByComparingTo("50.00");
    }

    @Test
    void sectionRevenueAddsUpToTheEventRevenue() {
        BigDecimal sectionSum = repository.sections(event.eventId()).stream().map(SectionStats::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sectionSum).isEqualByComparingTo(repository.orders(event.eventId()).revenue());
    }

    @Test
    void anEventWithoutOrdersHasZeroRevenueAndAllStatuses() {
        PublishedEvent quiet = data.publishedEvent(3, "5.00");

        ReportingRepository.OrderFigures figures = repository.orders(quiet.eventId());

        assertThat(figures.revenue()).isEqualByComparingTo("0");
        assertThat(figures.byStatus().values()).containsOnly(0L);
        assertThat(repository.sections(quiet.eventId()).get(0).seats().available()).isEqualTo(3);
    }

    // ---- one organizer ----

    @Test
    void organizerTotalsHaveOneRowPerOwnEventWithExactNumbers() {
        List<EventTotals> totals = repository.eventTotals(event.organizerId(), null, null);

        assertThat(totals).hasSize(1);
        EventTotals row = totals.get(0);
        assertThat(row.eventId()).isEqualTo(event.eventId());
        assertThat(row.totalSeats()).isEqualTo(10);
        assertThat(row.soldSeats()).isEqualTo(3);
        assertThat(row.heldSeats()).isEqualTo(1);
        assertThat(row.paidOrders()).isEqualTo(2);
        assertThat(row.revenue()).isEqualByComparingTo("50.00");
    }

    @Test
    void anotherOrganizersEventsAreNotIncluded() {
        PublishedEvent other = data.publishedEvent(4, "99.00");   // has its own organizer

        assertThat(repository.eventTotals(event.organizerId(), null, null))
                .extracting(EventTotals::eventId).containsExactly(event.eventId())
                .doesNotContain(other.eventId());
        assertThat(repository.eventTotals(other.organizerId(), null, null))
                .extracting(EventTotals::eventId).containsExactly(other.eventId());
    }

    @Test
    void theDateRangeFiltersEventsByStartAndIncludesEventsWithoutSales() {
        long organizer = event.organizerId();
        PublishedEvent later = data.publishedEvent(2, "5.00");
        jdbc.update("update events set organizer_id = ?, starts_at = now() + interval '90 days' where id = ?",
                organizer, later.eventId());
        Instant day60 = Instant.now().plus(Duration.ofDays(60));

        assertThat(repository.eventTotals(organizer, null, null)).extracting(EventTotals::eventId)
                .containsExactly(event.eventId(), later.eventId());           // ordered by start
        assertThat(repository.eventTotals(organizer, null, day60)).extracting(EventTotals::eventId)
                .containsExactly(event.eventId());
        assertThat(repository.eventTotals(organizer, day60, null)).extracting(EventTotals::eventId)
                .containsExactly(later.eventId());
        assertThat(repository.eventTotals(organizer, day60, null).get(0).revenue()).isEqualByComparingTo("0");
        assertThat(repository.eventTotals(organizer, Instant.now().plus(Duration.ofDays(200)), null)).isEmpty();
    }

    @Test
    void aDraftEventWithoutSeatsIsListedWithZeros() {
        long organizer = event.organizerId();
        long draft = jdbc.queryForObject(
                "insert into events (organizer_id, venue_id, title, category, starts_at, status, created_at, updated_at) "
                        + "values (?, ?, 'Draft', 'CONCERT', now() + interval '10 days', 'DRAFT', now(), now()) "
                        + "returning id", Long.class, organizer, event.venueId());

        EventTotals row = repository.eventTotals(organizer, null, null).stream()
                .filter(e -> e.eventId() == draft).findFirst().orElseThrow();

        assertThat(row.totalSeats()).isZero();
        assertThat(row.soldSeats()).isZero();
        assertThat(row.revenue()).isEqualByComparingTo("0");
    }
}
