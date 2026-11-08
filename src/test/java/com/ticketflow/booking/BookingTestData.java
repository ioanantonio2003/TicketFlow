package com.ticketflow.booking;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fast SQL fixtures for booking tests: a published event whose seats are already generated, users and
 * orders. Plain SQL because the venue and event entities belong to other features. Every call creates
 * fresh, uniquely named data, so tests never depend on each other.
 */
public final class BookingTestData {

    /** A published event with its seats; {@code seatIds} are event-seat ids in seat order. */
    public record PublishedEvent(long eventId, long organizerId, long venueId, List<Long> seatIds) {
    }

    private final JdbcTemplate jdbc;

    public BookingTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long user(String role) {
        return jdbc.queryForObject(
                "insert into users (email, password_hash, display_name, role, created_at, updated_at) "
                        + "values (?, 'x', 'Test', ?, now(), now()) returning id", Long.class,
                role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com", role);
    }

    /** One section with one row of {@code seats} seats, all priced the same, all AVAILABLE, starting in 30 days. */
    public PublishedEvent publishedEvent(int seats, String price) {
        long organizer = user("ORGANIZER");
        long venue = jdbc.queryForObject(
                "insert into venues (organizer_id, name, city, address, created_at, updated_at) "
                        + "values (?, 'Arena', 'Cluj', 'Street 1', now(), now()) returning id", Long.class, organizer);
        long section = jdbc.queryForObject(
                "insert into sections (venue_id, name, sort_order) values (?, 'Floor', 0) returning id", Long.class,
                venue);
        jdbc.update("insert into seats (section_id, row_label, seat_number) "
                + "select ?, '1', n from generate_series(1, ?) n", section, seats);
        long event = jdbc.queryForObject(
                "insert into events (organizer_id, venue_id, title, category, starts_at, status, created_at, updated_at) "
                        + "values (?, ?, 'Concert', 'CONCERT', now() + interval '30 days', 'PUBLISHED', now(), now()) "
                        + "returning id", Long.class, organizer, venue);
        long priceCategory = jdbc.queryForObject(
                "insert into price_categories (event_id, section_id, price) values (?, ?, ?) returning id",
                Long.class, event, section, new BigDecimal(price));
        jdbc.update("insert into event_seats (event_id, seat_id, price_category_id, status, version) "
                + "select ?, s.id, ?, 'AVAILABLE', 0 from seats s where s.section_id = ?", event, priceCategory, section);
        List<Long> seatIds = jdbc.queryForList(
                "select id from event_seats where event_id = ? order by id", Long.class, event);
        return new PublishedEvent(event, organizer, venue, seatIds);
    }

    public long order(long customerId, long eventId, String status, Instant holdExpiresAt) {
        return jdbc.queryForObject(
                "insert into orders (customer_id, event_id, status, hold_expires_at, total_amount, created_at, updated_at) "
                        + "values (?, ?, ?, ?, 0, now(), now()) returning id", Long.class,
                customerId, eventId, status,
                holdExpiresAt == null ? null : OffsetDateTime.ofInstant(holdExpiresAt, ZoneOffset.UTC));
    }

    public void orderItem(long orderId, long eventSeatId, String price) {
        jdbc.update("insert into order_items (order_id, event_seat_id, price) values (?, ?, ?)",
                orderId, eventSeatId, new BigDecimal(price));
    }

    public int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    static Instant inFuture(Duration duration) {
        return Instant.now().plus(duration);
    }
}
