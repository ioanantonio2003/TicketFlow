package com.ticketflow.booking;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderRepository extends JpaRepository<Order, Long> {

    /** Ownership lives in the query: someone else's order is simply "not found". */
    Optional<Order> findByIdAndCustomerId(Long id, Long customerId);

    /**
     * Same as above but locks the row, so cancelling cannot interleave with the expiry job or a payment
     * touching the same order. The status is checked after the lock is held.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.customerId = :customerId")
    Optional<Order> findByIdAndCustomerIdForUpdate(@Param("id") Long id, @Param("customerId") Long customerId);

    /** Locks any order by id (no owner check): for the payment webhook, which acts for the system. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    /**
     * Claims PENDING orders whose hold ran out. {@code skip locked} makes a second instance running the same
     * job pass over rows the first one is processing instead of waiting or doing the work twice.
     */
    @Query(value = """
            select * from orders
            where status = 'PENDING' and hold_expires_at < :now
            order by hold_expires_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<Order> claimExpired(@Param("now") Instant now, @Param("batchSize") int batchSize);

    Page<Order> findByCustomerId(Long customerId, Pageable pageable);
}
