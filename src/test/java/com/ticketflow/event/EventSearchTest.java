package com.ticketflow.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.TestcontainersConfig;
import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.event.dto.PublicEventSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The public search against a real PostgreSQL. The venue/event tables are filled with plain SQL because
 * the venue entities are private to their feature. Each test creates venues with its own unique city,
 * so tests do not see each other's data and pass in any order.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, EventSearchService.class, EventReadRepository.class,
        EventSearchTest.FixedClock.class})
@ActiveProfiles("test")
class EventSearchTest {

    static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private EventSearchService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Long organizerId;
    private String city;      // unique per test
    private String otherCity; // unique per test
    private Long venue;
    private Long otherVenue;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        organizerId = jdbc.queryForObject(
                "insert into users (email, password_hash, display_name, role, created_at, updated_at) "
                        + "values (?, 'x', 'x', 'ORGANIZER', now(), now()) returning id", Long.class,
                "o" + suffix + "@example.com");
        city = "Cluj" + suffix;
        otherCity = "Bucharest" + suffix;
        venue = insertVenue("Arena", city);
        otherVenue = insertVenue("Theatre", otherCity);
    }

    // ---- what is visible at all ----

    @Test
    void onlyPublishedFutureEventsAreListed() {
        insertEvent(venue, "Visible", "CONCERT", days(10), "PUBLISHED", "100");
        insertEvent(venue, "Draft", "CONCERT", days(10), "DRAFT", "100");
        insertEvent(venue, "Past", "CONCERT", days(-5), "PUBLISHED", "100");
        insertEvent(venue, "Cancelled", "CONCERT", days(10), "CANCELLED", "100");
        insertEvent(venue, "Completed", "CONCERT", days(-1), "COMPLETED", "100");

        assertThat(titles(service.search(city, null, null, null, null, null, page(0, 20)))).containsExactly("Visible");
    }

    @Test
    void noFiltersListsEverythingByStartDateAscending() {
        insertEvent(venue, "C", "CONCERT", days(30), "PUBLISHED", "10");
        insertEvent(otherVenue, "A", "THEATRE", days(10), "PUBLISHED", "10");
        insertEvent(venue, "B", "SPORT", days(20), "PUBLISHED", "10");

        PageResponse<PublicEventSummary> result = service.search(null, null, null, null, null, null, page(0, 100));

        List<String> mine = titles(result).stream().filter(t -> List.of("A", "B", "C").contains(t)).toList();
        assertThat(mine).containsExactly("A", "B", "C");
    }

    // ---- each filter ----

    @Test
    void cityFilterIgnoresCase() {
        insertEvent(venue, "In first city", "CONCERT", days(10), "PUBLISHED", "10");
        insertEvent(otherVenue, "In other city", "CONCERT", days(10), "PUBLISHED", "10");

        assertThat(titles(service.search(city.toLowerCase(), null, null, null, null, null, page(0, 20))))
                .containsExactly("In first city");
        assertThat(titles(service.search(otherCity.toUpperCase(), null, null, null, null, null, page(0, 20))))
                .containsExactly("In other city");
    }

    @Test
    void categoryFilter() {
        insertEvent(venue, "Rock", "CONCERT", days(10), "PUBLISHED", "10");
        insertEvent(venue, "Hamlet", "THEATRE", days(11), "PUBLISHED", "10");

        assertThat(titles(service.search(city, EventCategory.THEATRE, null, null, null, null, page(0, 20))))
                .containsExactly("Hamlet");
    }

    @Test
    void dateRangeIsInclusive() {
        insertEvent(venue, "Early", "CONCERT", days(5), "PUBLISHED", "10");
        insertEvent(venue, "Inside", "CONCERT", days(15), "PUBLISHED", "10");
        insertEvent(venue, "Edge", "CONCERT", days(20), "PUBLISHED", "10");
        insertEvent(venue, "Late", "CONCERT", days(40), "PUBLISHED", "10");

        assertThat(titles(service.search(city, null, NOW.plus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(20)),
                null, null, page(0, 20)))).containsExactly("Inside", "Edge");
        assertThat(titles(service.search(city, null, NOW.plus(Duration.ofDays(30)), null, null, null, page(0, 20))))
                .containsExactly("Late");
        assertThat(titles(service.search(city, null, null, NOW.plus(Duration.ofDays(6)), null, null, page(0, 20))))
                .containsExactly("Early");
    }

    @Test
    void maxPriceMatchesEventsWithAnySectionWithinBudget() {
        Long cheapAndDear = insertEvent(venue, "Mixed", "CONCERT", days(10), "PUBLISHED", "40");
        addPrice(cheapAndDear, "300");
        insertEvent(venue, "Exactly", "CONCERT", days(11), "PUBLISHED", "100");
        insertEvent(venue, "Expensive", "CONCERT", days(12), "PUBLISHED", "100.01");

        assertThat(titles(service.search(city, null, null, null, new BigDecimal("100"), null, page(0, 20))))
                .containsExactly("Mixed", "Exactly");
        assertThat(titles(service.search(city, null, null, null, new BigDecimal("39.99"), null, page(0, 20))))
                .isEmpty();
    }

    @Test
    void titleSearchIsCaseInsensitiveAndTreatsWildcardsLiterally() {
        insertEvent(venue, "Jazz Evening", "CONCERT", days(10), "PUBLISHED", "10");
        insertEvent(venue, "Rock Night", "CONCERT", days(11), "PUBLISHED", "10");
        insertEvent(venue, "100%_Fun", "OTHER", days(12), "PUBLISHED", "10");

        assertThat(titles(service.search(city, null, null, null, null, "JAZZ", page(0, 20)))).containsExactly("Jazz Evening");
        assertThat(titles(service.search(city, null, null, null, null, "night", page(0, 20)))).containsExactly("Rock Night");
        // "%" and "_" must not act as wildcards
        assertThat(titles(service.search(city, null, null, null, null, "%", page(0, 20)))).containsExactly("100%_Fun");
        assertThat(titles(service.search(city, null, null, null, null, "_", page(0, 20)))).containsExactly("100%_Fun");
        assertThat(titles(service.search(city, null, null, null, null, "z%z", page(0, 20)))).isEmpty();
    }

    @Test
    void blankFiltersAreIgnored() {
        insertEvent(venue, "Any", "CONCERT", days(10), "PUBLISHED", "10");

        assertThat(titles(service.search(city, null, null, null, null, "   ", page(0, 20)))).containsExactly("Any");
        assertThat(titles(service.search("  ", null, null, null, null, "Any", page(0, 100)))).contains("Any");
    }

    @Test
    void filtersCombineWithAnd() {
        insertEvent(venue, "Cheap concert here", "CONCERT", days(10), "PUBLISHED", "50");
        insertEvent(venue, "Dear concert here", "CONCERT", days(11), "PUBLISHED", "500");
        insertEvent(venue, "Cheap play here", "THEATRE", days(12), "PUBLISHED", "50");
        insertEvent(otherVenue, "Cheap concert there", "CONCERT", days(13), "PUBLISHED", "50");

        PageResponse<PublicEventSummary> result = service.search(city, EventCategory.CONCERT,
                NOW, NOW.plus(Duration.ofDays(100)), new BigDecimal("100"), "cheap", page(0, 20));

        assertThat(titles(result)).containsExactly("Cheap concert here");
    }

    // ---- paging and sorting ----

    @Test
    void paginationReturnsTotalsAndSlices() {
        for (int i = 1; i <= 5; i++) {
            insertEvent(venue, "Event " + i, "CONCERT", days(i), "PUBLISHED", "10");
        }

        PageResponse<PublicEventSummary> first = service.search(city, null, null, null, null, null, page(0, 2));
        PageResponse<PublicEventSummary> last = service.search(city, null, null, null, null, null, page(2, 2));

        assertThat(first.totalElements()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(titles(first)).containsExactly("Event 1", "Event 2");
        assertThat(titles(last)).containsExactly("Event 5");
    }

    @Test
    void sortableByStartDateTitleAndMinPrice() {
        insertEvent(venue, "Bravo", "CONCERT", days(30), "PUBLISHED", "200");
        insertEvent(venue, "alpha", "CONCERT", days(10), "PUBLISHED", "300");
        insertEvent(venue, "Charlie", "CONCERT", days(20), "PUBLISHED", "100");

        assertThat(titles(sorted("startsAt", Sort.Direction.DESC))).containsExactly("Bravo", "Charlie", "alpha");
        assertThat(titles(sorted("title", Sort.Direction.ASC))).containsExactly("alpha", "Bravo", "Charlie");
        assertThat(titles(sorted("minPrice", Sort.Direction.ASC))).containsExactly("Charlie", "Bravo", "alpha");
        assertThat(titles(sorted("minPrice", Sort.Direction.DESC))).containsExactly("alpha", "Bravo", "Charlie");
    }

    @Test
    void eventsWithTheSameSortKeyKeepAStableOrderAcrossPages() {
        for (int i = 0; i < 5; i++) {
            insertEvent(venue, "Same title", "CONCERT", days(10), "PUBLISHED", "10"); // same date and title
        }

        List<Long> seen = new java.util.ArrayList<>();
        for (int page = 0; page < 5; page++) {
            seen.addAll(service.search(city, null, null, null, null, null, page(page, 1)).content().stream()
                    .map(PublicEventSummary::id).toList());
        }

        assertThat(seen).doesNotHaveDuplicates().hasSize(5).isSorted(); // id is the tie-breaker
    }

    // ---- values in the response ----

    @Test
    void responseHasVenueMinPriceAndAvailableSeats() {
        Long event = insertEvent(venue, "Full info", "CONCERT", days(10), "PUBLISHED", "80");
        Long second = addPrice(event, "45.50");
        insertEventSeats(event, 6, 2, 1); // 6 seats: AVAILABLE x3 (6-2-1), HELD x2, SOLD x1

        PublicEventSummary summary = service.search(city, null, null, null, null, null, page(0, 5)).content().get(0);

        assertThat(summary.venueName()).isEqualTo("Arena");
        assertThat(summary.city()).isEqualTo(city);
        assertThat(summary.minPrice()).isEqualByComparingTo("45.50");
        assertThat(summary.availableSeats()).isEqualTo(3);
        assertThat(second).isNotNull();
    }

    @Test
    void eventWithoutSeatsShowsZeroAvailable() {
        insertEvent(venue, "No seats yet", "CONCERT", days(10), "PUBLISHED", "80");

        assertThat(service.search(city, null, null, null, null, null, page(0, 5)).content().get(0).availableSeats())
                .isZero();
    }

    // ---- no N+1 ----

    @Test
    void sqlStatementCountDoesNotDependOnTheNumberOfEvents() {
        String suffix = String.valueOf(System.nanoTime());
        // every event in its own venue and with seats: lazy venues or per-event queries would multiply statements
        String smallCity = "Small" + suffix;
        String bigCity = "Big" + suffix;
        for (int i = 0; i < 5; i++) {
            insertEventWithSeats(insertVenue("V" + i, smallCity), "S" + i, 3);
        }
        for (int i = 0; i < 30; i++) {
            insertEventWithSeats(insertVenue("V" + i, bigCity), "B" + i, 3);
        }
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();

        long small = hibernateStatementsFor(statistics, smallCity);
        long big = hibernateStatementsFor(statistics, bigCity);

        assertThat(small).isEqualTo(big);
        assertThat(big).isLessThanOrEqualTo(2); // the page query and the count query
    }

    // ---- bad input ----

    @Test
    void unsupportedSortPropertyIsRejected() {
        assertThatThrownBy(() -> service.search(null, null, null, null, null, null,
                PageRequest.of(0, 10, Sort.by("organizerId"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("organizerId")
                .hasMessageContaining("minPrice");
    }

    @Test
    void invalidRangesAreRejected() {
        assertThatThrownBy(() -> service.search(null, null, NOW.plusSeconds(10), NOW, null, null, page(0, 10)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.search(null, null, null, null, new BigDecimal("-1"), null, page(0, 10)))
                .isInstanceOf(BadRequestException.class);
    }

    // ---- helpers ----

    private long hibernateStatementsFor(Statistics statistics, String cityName) {
        entityManager.clear();
        long before = statistics.getPrepareStatementCount();
        PageResponse<PublicEventSummary> page = service.search(cityName, null, null, null, null, null, page(0, 5));
        assertThat(page.content()).hasSize(5).allSatisfy(e -> {
            assertThat(e.city()).isEqualTo(cityName);
            assertThat(e.availableSeats()).isEqualTo(3);
        });
        return statistics.getPrepareStatementCount() - before;
    }

    private PageResponse<PublicEventSummary> sorted(String property, Sort.Direction direction) {
        return service.search(city, null, null, null, null, null, PageRequest.of(0, 20, Sort.by(direction, property)));
    }

    private static Pageable page(int page, int size) {
        return PageRequest.of(page, size);
    }

    private static Instant days(int days) {
        return NOW.plus(Duration.ofDays(days));
    }

    private static List<String> titles(PageResponse<PublicEventSummary> page) {
        return page.content().stream().map(PublicEventSummary::title).toList();
    }

    private Long insertVenue(String name, String venueCity) {
        return jdbc.queryForObject(
                "insert into venues (organizer_id, name, city, address, created_at, updated_at) "
                        + "values (?, ?, ?, 'Street 1', now(), now()) returning id", Long.class,
                organizerId, name, venueCity);
    }

    /** Inserts an event with one section and one price category; returns the event id. */
    private Long insertEvent(Long venueId, String title, String category, Instant startsAt, String status,
                             String price) {
        Long eventId = jdbc.queryForObject(
                "insert into events (organizer_id, venue_id, title, category, starts_at, status, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, ?, now(), now()) returning id", Long.class,
                organizerId, venueId, title, category, OffsetDateTime.ofInstant(startsAt, ZoneOffset.UTC), status);
        addPrice(eventId, price);
        return eventId;
    }

    /** Adds another section (with a price) to the venue of the event. */
    private Long addPrice(Long eventId, String price) {
        Long venueId = jdbc.queryForObject("select venue_id from events where id = ?", Long.class, eventId);
        Long section = jdbc.queryForObject(
                "insert into sections (venue_id, name, sort_order) values (?, ?, 0) returning id", Long.class,
                venueId, "S" + System.nanoTime());
        return jdbc.queryForObject("insert into price_categories (event_id, section_id, price) values (?, ?, ?) "
                + "returning id", Long.class, eventId, section, new BigDecimal(price));
    }

    /**
     * Creates seats in the event's first priced section and an event seat for each: {@code held} HELD,
     * {@code sold} SOLD and the rest AVAILABLE.
     */
    private void insertEventSeats(Long eventId, int seats, int held, int sold) {
        Long priceCategory = jdbc.queryForObject(
                "select id from price_categories where event_id = ? order by id limit 1", Long.class, eventId);
        Long section = jdbc.queryForObject("select section_id from price_categories where id = ?", Long.class,
                priceCategory);
        jdbc.update("insert into seats (section_id, row_label, seat_number) select ?, '1', n from generate_series(1, ?) n",
                section, seats);
        jdbc.update("""
                insert into event_seats (event_id, seat_id, price_category_id, status, version)
                select ?, s.id, ?, case when s.seat_number <= ? then 'HELD' when s.seat_number <= ? then 'SOLD'
                                        else 'AVAILABLE' end, 0
                from seats s where s.section_id = ?""", eventId, priceCategory, held, held + sold, section);
    }

    private void insertEventWithSeats(Long venueId, String title, int seats) {
        Long eventId = insertEvent(venueId, title, "CONCERT", days(10), "PUBLISHED", "10");
        insertEventSeats(eventId, seats, 0, 0);
    }
}
