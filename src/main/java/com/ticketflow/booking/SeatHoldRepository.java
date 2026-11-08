package com.ticketflow.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The SQL that changes seat state. The database is the single source of truth for seats: availability is
 * never checked in Java first (CLAUDE.md section 7). Every method must run inside the caller's transaction.
 */
@Repository
@RequiredArgsConstructor
class SeatHoldRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * Waits for a transaction-scoped advisory lock on (customer, event) and keeps it until the transaction
     * ends. Two requests of the same customer for the same event run one after the other, so the
     * "max seats per customer" check cannot be fooled by parallel requests. Requests of other customers or
     * for other events do not wait. A hash collision only makes two unrelated pairs wait for each other.
     */
    void lockCustomerForEvent(Long customerId, Long eventId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 0))",
                new MapSqlParameterSource("key", "booking:" + customerId + ":" + eventId),
                rs -> {
                });
    }

    /**
     * THE concurrency rule: one atomic conditional UPDATE. A seat changes to HELD only if it is still
     * AVAILABLE at the moment the row is updated; PostgreSQL re-checks the condition on a row that another
     * transaction just changed, so two requests can never both win the same seat. Seats of another event
     * are not touched. Returns how many seats were held; the caller compares it with the number requested.
     */
    int holdSeats(Long eventId, Collection<Long> eventSeatIds, Long orderId, Instant expiresAt) {
        return jdbc.update("""
                        update event_seats
                        set status = 'HELD', order_id = :orderId, hold_expires_at = :expiresAt, version = version + 1
                        where event_id = :eventId and id in (:seatIds) and status = 'AVAILABLE'
                        """,
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                        .addValue("eventId", eventId)
                        .addValue("seatIds", eventSeatIds));
    }

    /**
     * The mirror of {@link #holdSeats}: HELD -> AVAILABLE, only for seats still HELD by these orders. A seat
     * that is not HELD any more (for example already released by another instance) is not touched, so
     * running this twice is harmless. Returns the released seat ids per event.
     */
    Map<Long, List<Long>> releaseSeats(Collection<Long> orderIds) {
        return release("HELD", orderIds);
    }

    /** SOLD -> AVAILABLE for a refunded order (the caller has checked that the event allows it). */
    Map<Long, List<Long>> releaseSoldSeats(Long orderId) {
        return release("SOLD", List.of(orderId));
    }

    private Map<Long, List<Long>> release(String fromStatus, Collection<Long> orderIds) {
        Map<Long, List<Long>> released = new LinkedHashMap<>();
        jdbc.query("""
                        update event_seats
                        set status = 'AVAILABLE', order_id = null, hold_expires_at = null, version = version + 1
                        where order_id in (:orderIds) and status = :fromStatus
                        returning id, event_id
                        """,
                new MapSqlParameterSource("orderIds", orderIds).addValue("fromStatus", fromStatus),
                rs -> {
                    released.computeIfAbsent(rs.getLong("event_id"), k -> new ArrayList<>()).add(rs.getLong("id"));
                });
        return released;
    }

    /**
     * HELD -> SOLD for the seats this order still holds (same pattern as {@link #holdSeats}: the condition and
     * the write are one statement). Returns how many seats were sold.
     */
    int sellSeats(Long orderId) {
        return jdbc.update("""
                        update event_seats
                        set status = 'SOLD', hold_expires_at = null, version = version + 1
                        where order_id = :orderId and status = 'HELD'
                        """,
                new MapSqlParameterSource("orderId", orderId));
    }

    /** Price of each seat currently held by the order, taken from the seat's price category. */
    Map<Long, BigDecimal> pricesOfHeldSeats(Long orderId) {
        Map<Long, BigDecimal> prices = new LinkedHashMap<>();
        jdbc.query("""
                        select es.id, pc.price
                        from event_seats es
                        join price_categories pc on pc.id = es.price_category_id
                        where es.order_id = :orderId and es.status = 'HELD'
                        order by es.id
                        """,
                new MapSqlParameterSource("orderId", orderId),
                rs -> {
                    prices.put(rs.getLong("id"), rs.getBigDecimal("price"));
                });
        return prices;
    }
}
