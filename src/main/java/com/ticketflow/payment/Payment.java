package com.ticketflow.payment;

import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One attempt to pay an order. An order can have several (a failed attempt can be retried). */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Payment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Plain id: payment does not touch the order entity of the booking feature. */
    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "provider_payment_id", length = 100)
    private String providerPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Version
    private Long version;

    private Payment(Long orderId, BigDecimal amount) {
        this.orderId = orderId;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    static Payment pending(Long orderId, BigDecimal amount) {
        return new Payment(orderId, amount);
    }

    /** The provider accepted the payment and gave it its own id. */
    void attachProvider(String providerPaymentId) {
        this.providerPaymentId = providerPaymentId;
    }

    void succeed() {
        requirePending();
        this.status = PaymentStatus.SUCCEEDED;
    }

    void fail() {
        requirePending();
        this.status = PaymentStatus.FAILED;
    }

    /**
     * The money goes back: either it arrived but the order could not be paid any more (PENDING), or a paid order
     * was refunded (SUCCEEDED).
     */
    void refund() {
        if (status != PaymentStatus.PENDING && status != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("The payment is already " + status + ".");
        }
        this.status = PaymentStatus.REFUNDED;
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING) {
            throw new ConflictException("The payment is already " + status + ".");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Payment other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
