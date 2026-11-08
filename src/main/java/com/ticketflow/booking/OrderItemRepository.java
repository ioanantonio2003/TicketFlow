package com.ticketflow.booking;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /**
     * Seats the customer already has for the event: in PAID orders, and in PENDING orders whose hold has
     * not run out. Expired or cancelled orders do not count.
     */
    @Query("""
            select count(i) from OrderItem i
            where i.order.customerId = :customerId and i.order.eventId = :eventId
              and (i.order.status = com.ticketflow.booking.OrderStatus.PAID
                   or (i.order.status = com.ticketflow.booking.OrderStatus.PENDING
                       and i.order.holdExpiresAt > :now))
            """)
    long countActiveSeats(@Param("customerId") Long customerId, @Param("eventId") Long eventId,
                          @Param("now") Instant now);
}
