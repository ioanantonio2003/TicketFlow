package com.ticketflow.config;

import com.ticketflow.event.EventCategory;
import com.ticketflow.event.EventService;
import com.ticketflow.event.dto.CreateEventRequest;
import com.ticketflow.event.dto.SetPricesRequest;
import com.ticketflow.event.dto.SetPricesRequest.SectionPrice;
import com.ticketflow.user.Role;
import com.ticketflow.user.UserService;
import com.ticketflow.user.dto.UserResponse;
import com.ticketflow.venue.VenueService;
import com.ticketflow.venue.dto.CreateVenueRequest;
import com.ticketflow.venue.dto.RowRequest;
import com.ticketflow.venue.dto.SectionRequest;
import com.ticketflow.venue.dto.SectionResponse;
import com.ticketflow.venue.dto.VenueResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Local demo data (profile "dev" only): an organizer, a customer, two venues and eight published events.
 * It uses the same services as the API, so the data obeys the same rules. Safe to start repeatedly:
 * if the demo organizer already exists nothing is created.
 *
 * <p>Logins: organizer@ticketflow.local / organizer123, customer@ticketflow.local / customer123.
 * These passwords exist only in the dev profile.
 */
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class DevDataSeeder implements ApplicationRunner {

    static final String ORGANIZER_EMAIL = "organizer@ticketflow.local";
    static final String CUSTOMER_EMAIL = "customer@ticketflow.local";

    private final UserService userService;
    private final VenueService venueService;
    private final EventService eventService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        if (userService.findCredentialsByEmail(ORGANIZER_EMAIL).isPresent()) {
            return; // already seeded
        }
        UserResponse organizer = userService.createCustomer(
                ORGANIZER_EMAIL, passwordEncoder.encode("organizer123"), "Demo Organizer");
        userService.changeRole(organizer.id(), Role.ORGANIZER);
        userService.createCustomer(CUSTOMER_EMAIL, passwordEncoder.encode("customer123"), "Demo Customer");

        VenueResponse cluj = venueService.create(organizer.id(), new CreateVenueRequest(
                "Cluj Arena Hall", "Cluj", "Observatorului Street 10",
                List.of(section("Floor", 6, 20), section("Balcony", 3, 16), section("VIP", 2, 10))));
        VenueResponse bucharest = venueService.create(organizer.id(), new CreateVenueRequest(
                "Bucharest Grand Theatre", "Bucharest", "Victoriei Avenue 5",
                List.of(section("Stalls", 8, 15), section("Balcony", 4, 12))));

        Long who = organizer.id();
        publish(who, cluj, "Rock Night", EventCategory.CONCERT, 10, "120", "80", "200");
        publish(who, cluj, "Jazz Evening", EventCategory.CONCERT, 20, "90", "60", "150");
        publish(who, cluj, "Tech Summit", EventCategory.CONFERENCE, 35, "300", "150", "500");
        publish(who, cluj, "Derby Match", EventCategory.SPORT, 15, "70", "40", "100");
        publish(who, bucharest, "Hamlet", EventCategory.THEATRE, 12, "100", "60");
        publish(who, bucharest, "Swan Lake", EventCategory.THEATRE, 40, "150", "90");
        publish(who, bucharest, "Pop Stars Live", EventCategory.CONCERT, 25, "180", "110");
        publish(who, bucharest, "Charity Gala", EventCategory.OTHER, 50, "250", "200");
        log.info("Seeded demo data: 2 users, 2 venues, 8 published events");
    }

    /** A section with rows labelled 1..rows, each with the given number of seats. */
    private static SectionRequest section(String name, int rows, int seatsPerRow) {
        return new SectionRequest(name, IntStream.rangeClosed(1, rows)
                .mapToObj(row -> new RowRequest(String.valueOf(row), seatsPerRow)).toList());
    }

    /** Creates a DRAFT event, prices the venue's sections in order (one price each) and publishes it. */
    private void publish(Long organizerId, VenueResponse venue, String title, EventCategory category,
                         int daysAhead, String... prices) {
        // always in the future, at 19:00 UTC
        Instant startsAt = clock.instant().truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(daysAhead))
                .plus(Duration.ofHours(19));
        Long eventId = eventService.create(organizerId, new CreateEventRequest(
                venue.id(), title, title + " at " + venue.name(), category, startsAt)).id();

        List<SectionResponse> sections = venue.sections();
        List<SectionPrice> sectionPrices = IntStream.range(0, sections.size())
                .mapToObj(i -> new SectionPrice(sections.get(i).id(), new BigDecimal(prices[i]))).toList();
        eventService.setPrices(organizerId, eventId, new SetPricesRequest(sectionPrices));
        eventService.publish(organizerId, eventId);
    }
}
