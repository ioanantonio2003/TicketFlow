package com.ticketflow.reporting;

import com.ticketflow.booking.OrderStatus;
import com.ticketflow.reporting.dto.EventTotals;
import com.ticketflow.reporting.dto.SeatCounts;
import com.ticketflow.reporting.dto.SectionStats;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only reporting queries: grouped SQL straight into small result rows, no entities. Reporting is a read
 * model over the tables of several features, so it joins them in SQL instead of calling five services and
 * adding up in Java; the ownership check (who may see which event) is done by the caller through EventCatalog.
 * The number of queries does not depend on the number of events, sections or orders.
 */
@Repository
@RequiredArgsConstructor
class ReportingRepository {

    /** Orders of one event: how many per status, and the money of the PAID ones. */
    record OrderFigures(Map<OrderStatus, Long> byStatus, BigDecimal revenue) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /** Per-section seat counts and revenue of PAID orders, in the venue's section order. */
    List<SectionStats> sections(Long eventId) {
        return jdbc.query("""
                        select sec.id, sec.name,
                               count(*) as total,
                               count(*) filter (where es.status = 'AVAILABLE') as available,
                               count(*) filter (where es.status = 'HELD') as held,
                               count(*) filter (where es.status = 'SOLD') as sold,
                               coalesce(rev.revenue, 0) as revenue
                        from event_seats es
                        join seats s on s.id = es.seat_id
                        join sections sec on sec.id = s.section_id
                        left join (
                            select s2.section_id, sum(oi.price) as revenue
                            from order_items oi
                            join orders o on o.id = oi.order_id and o.status = 'PAID'
                            join event_seats es2 on es2.id = oi.event_seat_id
                            join seats s2 on s2.id = es2.seat_id
                            where o.event_id = :eventId
                            group by s2.section_id
                        ) rev on rev.section_id = sec.id
                        where es.event_id = :eventId
                        group by sec.id, sec.name, sec.sort_order, rev.revenue
                        order by sec.sort_order, sec.id
                        """,
                new MapSqlParameterSource("eventId", eventId),
                (rs, i) -> new SectionStats(rs.getLong("id"), rs.getString("name"),
                        new SeatCounts(rs.getLong("total"), rs.getLong("available"), rs.getLong("held"),
                                rs.getLong("sold")),
                        null, rs.getBigDecimal("revenue")));
    }

    OrderFigures orders(Long eventId) {
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : OrderStatus.values()) {
            byStatus.put(status, 0L);
        }
        BigDecimal[] revenue = {BigDecimal.ZERO};
        jdbc.query("""
                        select status, count(*) as orders, sum(total_amount) as amount
                        from orders where event_id = :eventId group by status
                        """,
                new MapSqlParameterSource("eventId", eventId),
                rs -> {
                    OrderStatus status = OrderStatus.valueOf(rs.getString("status"));
                    byStatus.put(status, rs.getLong("orders"));
                    if (status == OrderStatus.PAID) {
                        revenue[0] = rs.getBigDecimal("amount");
                    }
                });
        return new OrderFigures(byStatus, revenue[0]);
    }

    /** One row per event of the organizer that starts in [from, to); one query for all of them. */
    List<EventTotals> eventTotals(Long organizerId, Instant from, Instant to) {
        return jdbc.query("""
                        select e.id, e.title, e.status, e.starts_at,
                               coalesce(seats.total, 0) as total_seats,
                               coalesce(seats.sold, 0) as sold_seats,
                               coalesce(seats.held, 0) as held_seats,
                               coalesce(paid.orders, 0) as paid_orders,
                               coalesce(paid.revenue, 0) as revenue
                        from events e
                        left join (
                            select es.event_id, count(*) as total,
                                   count(*) filter (where es.status = 'SOLD') as sold,
                                   count(*) filter (where es.status = 'HELD') as held
                            from event_seats es
                            where es.event_id in (select id from events where organizer_id = :organizerId)
                            group by es.event_id
                        ) seats on seats.event_id = e.id
                        left join (
                            select o.event_id, count(*) as orders, sum(o.total_amount) as revenue
                            from orders o
                            where o.status = 'PAID'
                              and o.event_id in (select id from events where organizer_id = :organizerId)
                            group by o.event_id
                        ) paid on paid.event_id = e.id
                        where e.organizer_id = :organizerId
                          and (cast(:from as timestamptz) is null or e.starts_at >= cast(:from as timestamptz))
                          and (cast(:to as timestamptz) is null or e.starts_at < cast(:to as timestamptz))
                        order by e.starts_at, e.id
                        """,
                new MapSqlParameterSource().addValue("organizerId", organizerId)
                        .addValue("from", from == null ? null : OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                        .addValue("to", to == null ? null : OffsetDateTime.ofInstant(to, ZoneOffset.UTC)),
                (rs, i) -> new EventTotals(rs.getLong("id"), rs.getString("title"), rs.getString("status"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(), rs.getLong("total_seats"),
                        rs.getLong("sold_seats"), rs.getLong("held_seats"), null, rs.getLong("paid_orders"),
                        rs.getBigDecimal("revenue")));
    }
}
