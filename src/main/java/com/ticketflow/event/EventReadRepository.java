package com.ticketflow.event;

import com.ticketflow.event.dto.EventDetailsResponse.SectionAvailability;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only queries that aggregate over several tables and return plain values. They run as SQL
 * because they join the venue's tables and event seats, and no entity is needed to read them.
 */
@Repository
@RequiredArgsConstructor
class EventReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    /** Seat row as stored: the event seat id, its status, and where the seat is. */
    record SeatRow(long eventSeatId, SeatStatus status, long sectionId, String sectionName, BigDecimal price,
                   String rowLabel, int seatNumber) {
    }

    /** Cheapest section price of each event, in one grouped query. */
    Map<Long, BigDecimal> minPrices(Collection<Long> eventIds) {
        Map<Long, BigDecimal> result = new HashMap<>();
        jdbc.query("select event_id, min(price) as min_price from price_categories where event_id in (:ids) "
                        + "group by event_id",
                new MapSqlParameterSource("ids", eventIds),
                rs -> {
                    result.put(rs.getLong("event_id"), rs.getBigDecimal("min_price"));
                });
        return result;
    }

    /** Number of AVAILABLE seats of each event, in one grouped query (uses the (event_id, status) index). */
    Map<Long, Long> availableSeats(Collection<Long> eventIds) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.query("select event_id, count(*) as available from event_seats "
                        + "where event_id in (:ids) and status = 'AVAILABLE' group by event_id",
                new MapSqlParameterSource("ids", eventIds),
                rs -> {
                    result.put(rs.getLong("event_id"), rs.getLong("available"));
                });
        return result;
    }

    List<SectionAvailability> sectionAvailability(Long eventId) {
        return jdbc.query("""
                        select pc.section_id, sec.name, pc.price,
                               count(es.id) filter (where es.status = 'AVAILABLE') as available,
                               count(es.id) as total
                        from price_categories pc
                        join sections sec on sec.id = pc.section_id
                        left join event_seats es on es.price_category_id = pc.id
                        where pc.event_id = :eventId
                        group by pc.section_id, sec.name, sec.sort_order, pc.price
                        order by sec.sort_order, pc.section_id
                        """,
                new MapSqlParameterSource("eventId", eventId),
                (rs, rowNum) -> new SectionAvailability(rs.getLong("section_id"), rs.getString("name"),
                        rs.getBigDecimal("price"), rs.getLong("available"), rs.getLong("total")));
    }

    /** All seats of the event in display order: by section, then in the order the venue created them. */
    List<SeatRow> seatMap(Long eventId) {
        return jdbc.query("""
                        select es.id, es.status, sec.id as section_id, sec.name as section_name, pc.price,
                               seat.row_label, seat.seat_number
                        from event_seats es
                        join seats seat on seat.id = es.seat_id
                        join sections sec on sec.id = seat.section_id
                        join price_categories pc on pc.id = es.price_category_id
                        where es.event_id = :eventId
                        order by sec.sort_order, sec.id, seat.id
                        """,
                new MapSqlParameterSource("eventId", eventId),
                (rs, rowNum) -> new SeatRow(rs.getLong("id"), SeatStatus.valueOf(rs.getString("status")),
                        rs.getLong("section_id"), rs.getString("section_name"), rs.getBigDecimal("price"),
                        rs.getString("row_label"), rs.getInt("seat_number")));
    }
}
