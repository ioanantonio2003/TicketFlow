package com.ticketflow.ticket;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface TicketRepository extends JpaRepository<Ticket, Long> {

    /** Ownership lives in the query: someone else's ticket is simply "not found". */
    Optional<Ticket> findByIdAndCustomerId(Long id, Long customerId);

    Page<Ticket> findByCustomerId(Long customerId, Pageable pageable);

    Optional<Ticket> findByCode(String code);

    @Query("select t.status from Ticket t where t.id = :id")
    TicketStatus findStatusById(@Param("id") Long id);

    boolean existsByOrderId(Long orderId);

    /**
     * VALID -> USED in one statement. The condition and the write cannot be separated: of two scanners reading
     * the same ticket at once, only one finds it VALID when its row is updated. Returns 1 for the winner, 0 for
     * everybody else. (Same idea as the seat UPDATE in CLAUDE.md section 7.)
     */
    @Modifying
    @Query("""
            update Ticket t set t.status = com.ticketflow.ticket.TicketStatus.USED, t.usedAt = :now, t.updatedAt = :now
            where t.id = :id and t.status = com.ticketflow.ticket.TicketStatus.VALID
            """)
    int markUsed(@Param("id") Long id, @Param("now") Instant now);
}
