package com.ticketflow.booking;

import com.ticketflow.booking.dto.OrderItemResponse;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only query that describes the seats of orders (section, row, number), for a whole page at once. */
@Repository
@RequiredArgsConstructor
class OrderReadRepository {

    private final NamedParameterJdbcTemplate jdbc;

    Map<Long, List<OrderItemResponse>> itemsByOrder(Collection<Long> orderIds) {
        Map<Long, List<OrderItemResponse>> result = new LinkedHashMap<>();
        if (orderIds.isEmpty()) {
            return result;
        }
        jdbc.query("""
                        select oi.order_id, oi.event_seat_id, oi.price, sec.name as section_name,
                               seat.row_label, seat.seat_number
                        from order_items oi
                        join event_seats es on es.id = oi.event_seat_id
                        join seats seat on seat.id = es.seat_id
                        join sections sec on sec.id = seat.section_id
                        where oi.order_id in (:orderIds)
                        order by oi.id
                        """,
                new MapSqlParameterSource("orderIds", orderIds),
                rs -> {
                    result.computeIfAbsent(rs.getLong("order_id"), id -> new java.util.ArrayList<>())
                            .add(new OrderItemResponse(rs.getLong("event_seat_id"), rs.getString("section_name"),
                                    rs.getString("row_label"), rs.getInt("seat_number"), rs.getBigDecimal("price")));
                });
        return result;
    }
}
