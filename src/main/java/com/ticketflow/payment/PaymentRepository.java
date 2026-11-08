package com.ticketflow.payment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Locked, so two deliveries of the same webhook are handled one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.providerPaymentId = :providerPaymentId")
    Optional<Payment> findByProviderPaymentIdForUpdate(@Param("providerPaymentId") String providerPaymentId);
}
