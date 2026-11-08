package com.ticketflow.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.TestcontainersConfig;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The venue tables are filled with plain SQL because the venue entities are private to their feature.
 * Runs on real PostgreSQL: the bulk INSERT ... SELECT is native SQL.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, EventRepositoryTest.Auditing.class})
@ActiveProfiles("test")
class EventRepositoryTest {

    @TestConfiguration
    @EnableJpaAuditing
    static class Auditing {
    }

    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private PriceCategoryRepository priceCategoryRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Long organizerId;

    @BeforeEach
    void createOrganizer() {
        organizerId = jdbcTemplate.queryForObject(
                "insert into users (email, password_hash, display_name, role, created_at, updated_at) "
                        + "values (?, 'x', 'x', 'ORGANIZER', now(), now()) returning id", Long.class,
                "org-" + System.nanoTime() + "@example.com");
    }

    @Test
    void publishCreatesExactlyOneEventSeatPerVenueSeatWithTheRightPriceCategory() {
        // venue with sections of 20, 30 and 50 seats
        Long venueId = insertVenue(organizerId);
        Long floor = insertSection(venueId, "Floor", 0, 20);
        Long balcony = insertSection(venueId, "Balcony", 1, 30);
        Long gallery = insertSection(venueId, "Gallery", 2, 50);
        Event event = saveEvent(venueId);
        PriceCategory floorPrice = priceCategoryRepository.save(new PriceCategory(event, floor, new BigDecimal("90.00")));
        PriceCategory balconyPrice = priceCategoryRepository.save(new PriceCategory(event, balcony, new BigDecimal("60.00")));
        PriceCategory galleryPrice = priceCategoryRepository.save(new PriceCategory(event, gallery, new BigDecimal("30.00")));
        // another event of the same venue must not be affected
        Event otherEvent = saveEvent(venueId);
        priceCategoryRepository.save(new PriceCategory(otherEvent, floor, new BigDecimal("1.00")));
        priceCategoryRepository.flush();

        int created = eventRepository.generateEventSeats(event.getId(), venueId);

        assertThat(created).isEqualTo(100);
        assertThat(count("select count(*) from event_seats where event_id = ?", event.getId())).isEqualTo(100);
        assertThat(count("select count(*) from event_seats where event_id = ?", otherEvent.getId())).isZero();
        // one event seat per seat of the venue: no seat missing, none twice
        assertThat(count("""
                select count(distinct es.seat_id) from event_seats es
                join seats s on s.id = es.seat_id join sections c on c.id = s.section_id
                where es.event_id = ? and c.venue_id = ?""", event.getId(), venueId)).isEqualTo(100);
        // each seat got the price category of its own section
        Map<Long, Long> perCategory = jdbcTemplate.queryForList(
                        "select price_category_id, count(*) as n from event_seats where event_id = ? group by 1",
                        event.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(r -> (Long) r.get("price_category_id"), r -> (Long) r.get("n")));
        assertThat(perCategory).containsEntry(floorPrice.getId(), 20L)
                .containsEntry(balconyPrice.getId(), 30L)
                .containsEntry(galleryPrice.getId(), 50L);
        assertThat(count("select count(*) from event_seats where event_id = ? and status = 'AVAILABLE' "
                + "and order_id is null and hold_expires_at is null and version = 0", event.getId())).isEqualTo(100);
    }

    @Test
    void numberOfSqlStatementsDoesNotGrowWithTheNumberOfSeats() {
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        long small = statementsForPublishing(10);
        long large = statementsForPublishing(2000);

        assertThat(large).isEqualTo(small);
        assertThat(statistics.isStatisticsEnabled()).isTrue();
    }

    @Test
    void sectionWithoutPriceGetsNoEventSeats() {
        Long venueId = insertVenue(organizerId);
        Long priced = insertSection(venueId, "Priced", 0, 5);
        insertSection(venueId, "Unpriced", 1, 7);
        Event event = saveEvent(venueId);
        priceCategoryRepository.saveAndFlush(new PriceCategory(event, priced, new BigDecimal("10.00")));

        // the service refuses to publish in this case; the query itself only creates what it can price
        assertThat(eventRepository.generateEventSeats(event.getId(), venueId)).isEqualTo(5);
    }

    @Test
    void secondInsertForTheSameEventViolatesTheUniqueConstraint() {
        Long venueId = insertVenue(organizerId);
        Long section = insertSection(venueId, "A", 0, 3);
        Event event = saveEvent(venueId);
        priceCategoryRepository.saveAndFlush(new PriceCategory(event, section, new BigDecimal("10.00")));
        eventRepository.generateEventSeats(event.getId(), venueId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> eventRepository.generateEventSeats(event.getId(), venueId))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void ownershipLookupAndLockedLookup() {
        Long venueId = insertVenue(organizerId);
        Event event = saveEvent(venueId);
        Long other = jdbcTemplate.queryForObject(
                "insert into users (email, password_hash, display_name, role, created_at, updated_at) "
                        + "values (?, 'x', 'x', 'ORGANIZER', now(), now()) returning id", Long.class,
                "other-" + System.nanoTime() + "@example.com");

        assertThat(eventRepository.findByIdAndOrganizerId(event.getId(), organizerId)).isPresent();
        assertThat(eventRepository.findByIdAndOrganizerId(event.getId(), other)).isEmpty();
        assertThat(eventRepository.findByIdAndOrganizerIdForUpdate(event.getId(), organizerId)).isPresent();
        assertThat(eventRepository.findByIdAndOrganizerIdForUpdate(event.getId(), other)).isEmpty();
    }

    @Test
    void organizerListIsScopedAndPaged() {
        Long venueId = insertVenue(organizerId);
        saveEvent(venueId);
        saveEvent(venueId);
        saveEvent(venueId);

        var page = eventRepository.findByOrganizerId(organizerId, PageRequest.of(0, 2));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(eventRepository.existsByVenueId(venueId)).isTrue();
        assertThat(eventRepository.existsByVenueId(venueId + 1000)).isFalse();
    }

    @Test
    void priceMustBePositive() {
        Long venueId = insertVenue(organizerId);
        Long section = insertSection(venueId, "A", 0, 1);
        Event event = saveEvent(venueId);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> priceCategoryRepository.saveAndFlush(
                        new PriceCategory(event, section, BigDecimal.ZERO)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    // ---- helpers ----

    /** Number of prepared SQL statements used by one publish of a venue with the given number of seats. */
    private long statementsForPublishing(int seats) {
        Long venueId = insertVenue(organizerId);
        Long section = insertSection(venueId, "S", 0, seats);
        Event event = saveEvent(venueId);
        priceCategoryRepository.saveAndFlush(new PriceCategory(event, section, new BigDecimal("10.00")));
        entityManager.clear();

        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        long before = statistics.getPrepareStatementCount();
        assertThat(eventRepository.generateEventSeats(event.getId(), venueId)).isEqualTo(seats);
        return statistics.getPrepareStatementCount() - before;
    }

    private Event saveEvent(Long venueId) {
        return eventRepository.saveAndFlush(Event.draft(organizerId, venueId, "Concert", null,
                EventCategory.CONCERT, Instant.now().plusSeconds(86_400), Instant.now()));
    }

    private Long insertVenue(Long organizer) {
        return jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, city, address, created_at, updated_at) "
                        + "values (?, 'Arena', 'Cluj', 'Street 1', now(), now()) returning id", Long.class, organizer);
    }

    /** Creates a section with one row "1" and the given number of seats. */
    private Long insertSection(Long venueId, String name, int sortOrder, int seats) {
        Long sectionId = jdbcTemplate.queryForObject(
                "insert into sections (venue_id, name, sort_order) values (?, ?, ?) returning id", Long.class,
                venueId, name, sortOrder);
        jdbcTemplate.update("insert into seats (section_id, row_label, seat_number) "
                + "select ?, '1', n from generate_series(1, ?) as n", sectionId, seats);
        return sectionId;
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }
}
