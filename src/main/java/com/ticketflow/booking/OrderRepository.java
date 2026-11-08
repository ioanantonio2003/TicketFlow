package com.ticketflow.booking;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface OrderRepository extends JpaRepository<Order, Long> {

    /** Ownership lives in the query: someone else's order is simply "not found". */
    Optional<Order> findByIdAndCustomerId(Long id, Long customerId);

    Page<Order> findByCustomerId(Long customerId, Pageable pageable);
}
