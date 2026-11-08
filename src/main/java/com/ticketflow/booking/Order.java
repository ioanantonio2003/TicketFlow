package com.ticketflow.booking;

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
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A customer's order for seats of one event. A PENDING order owns the seats it holds until
 * {@code holdExpiresAt}. It becomes CANCELLED or EXPIRED here; payment transitions come in later steps.
 */
@Entity
@Table(name = "orders") // "order" is a reserved word
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Order extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Plain ids: booking does not touch the entities of the user and event features. */
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Version
    private Long version;

    private Order(Long customerId, Long eventId, Instant holdExpiresAt) {
        this.customerId = customerId;
        this.eventId = eventId;
        this.status = OrderStatus.PENDING;
        this.holdExpiresAt = holdExpiresAt;
    }

    /** A new order always starts PENDING with a deadline; the status is never chosen by the client. */
    static Order pending(Long customerId, Long eventId, Instant holdExpiresAt) {
        return new Order(customerId, eventId, holdExpiresAt);
    }

    /** Called once the held seats are known and priced. */
    void assignTotal(BigDecimal total) {
        this.totalAmount = total;
    }

    /** The customer gives the seats up. Only a PENDING order can be cancelled. */
    void cancel() {
        requirePending("cancelled");
        this.status = OrderStatus.CANCELLED;
    }

    /** The hold ran out without payment. Only a PENDING order can expire. */
    void expire() {
        requirePending("expired");
        this.status = OrderStatus.EXPIRED;
    }

    private void requirePending(String action) {
        if (status != OrderStatus.PENDING) {
            throw new ConflictException("Only a pending order can be " + action + ".");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Order other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
