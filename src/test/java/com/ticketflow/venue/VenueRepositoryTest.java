package com.ticketflow.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.TestcontainersConfig;
import com.ticketflow.venue.dto.RowRequest;
import com.ticketflow.venue.dto.SectionRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfig.class, VenueRepositoryTest.Auditing.class})
@ActiveProfiles("test")
class VenueRepositoryTest {

    /** Venue extends AuditableEntity, whose not-null timestamps are filled by auditing (not part of this slice). */
    @TestConfiguration
    @EnableJpaAuditing
    static class Auditing {
    }

    @Autowired
    private VenueRepository venueRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long organizerA;
    private Long organizerB;

    @BeforeEach
    void createOrganizers() {
        organizerA = insertUser("a-" + System.nanoTime() + "@example.com");
        organizerB = insertUser("b-" + System.nanoTime() + "@example.com");
    }

    @Test
    void ownershipQueryFindsOnlyTheOwnersVenue() {
        Venue venue = saveVenue(organizerA, "Arena", 2, 3);

        assertThat(venueRepository.findByIdAndOrganizerId(venue.getId(), organizerA)).isPresent();
        assertThat(venueRepository.findByIdAndOrganizerId(venue.getId(), organizerB)).isEmpty();
    }

    @Test
    void listIsScopedToTheOrganizerAndPaged() {
        saveVenue(organizerA, "C venue", 1, 1);
        saveVenue(organizerA, "A venue", 1, 1);
        saveVenue(organizerA, "B venue", 1, 1);
        saveVenue(organizerB, "Other", 1, 1);

        var page = venueRepository.findByOrganizerId(organizerA, PageRequest.of(0, 2, Sort.by("name")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(Venue::getName).containsExactly("A venue", "B venue");
    }

    @Test
    void seatCountsAreGroupedBySection() {
        Venue venue = new Venue(organizerA, "Arena", "Cluj", "Main street 1");
        VenueLayoutGenerator.generate(venue, List.of(
                new SectionRequest("Floor", List.of(new RowRequest("1", 4), new RowRequest("2", 4))),
                new SectionRequest("Balcony", List.of(new RowRequest("A", 5)))));
        venueRepository.saveAndFlush(venue);

        var counts = venueRepository.countSeatsBySection(venue.getId());

        assertThat(counts).extracting(SectionSeatCount::seatCount).containsExactlyInAnyOrder(8L, 5L);
    }

    @Test
    void auditColumnsAreFilled() {
        Venue venue = saveVenue(organizerA, "Arena", 1, 1);

        assertThat(venue.getCreatedAt()).isNotNull();
        assertThat(venue.getUpdatedAt()).isNotNull();
    }

    @Test
    void sameSeatTwiceInOneSectionViolatesTheUniqueConstraint() {
        Venue venue = new Venue(organizerA, "Arena", "Cluj", "Main street 1");
        Section section = venue.addSection("Floor");
        section.addRow("1", 2);
        section.addRow("1", 1); // seats 1-1 and 1-2 again: row 1 seat 1 exists twice

        assertThatThrownBy(() -> venueRepository.saveAndFlush(venue))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameSectionNameTwiceInOneVenueViolatesTheUniqueConstraint() {
        Venue venue = new Venue(organizerA, "Arena", "Cluj", "Main street 1");
        venue.addSection("Floor");
        venue.addSection("Floor");

        assertThatThrownBy(() -> venueRepository.saveAndFlush(venue))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAVenueRemovesItsSectionsAndSeats() {
        Venue venue = saveVenue(organizerA, "Arena", 2, 5);
        Long venueId = venue.getId();
        assertThat(count("seats")).isEqualTo(10);

        venueRepository.delete(venue);
        venueRepository.flush();

        assertThat(count("seats")).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from sections where venue_id = ?", Integer.class,
                venueId)).isZero();
    }

    private Venue saveVenue(Long organizerId, String name, int rows, int seatsPerRow) {
        Venue venue = new Venue(organizerId, name, "Cluj", "Main street 1");
        List<RowRequest> rowRequests = java.util.stream.IntStream.rangeClosed(1, rows)
                .mapToObj(i -> new RowRequest(String.valueOf(i), seatsPerRow)).toList();
        VenueLayoutGenerator.generate(venue, List.of(new SectionRequest("Floor", rowRequests)));
        return venueRepository.saveAndFlush(venue);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private Long insertUser(String email) {
        return jdbcTemplate.queryForObject(
                "insert into users (email, password_hash, display_name, role, created_at, updated_at) "
                        + "values (?, 'x', 'x', 'ORGANIZER', now(), now()) returning id", Long.class, email);
    }
}
