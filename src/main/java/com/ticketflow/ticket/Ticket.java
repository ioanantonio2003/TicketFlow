package com.ticketflow.ticket;

import com.ticketflow.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One admission for one seat. It is a snapshot (event title, section, row, number are copied) and refers to
 * other features only by id. VALID -> USED is done by a conditional UPDATE in the repository, not by a setter,
 * because two scanners may read the same ticket at the same time.
 */
@Entity
@Table(name = "tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Ticket extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private Long eventId;

    @Column(name = "event_seat_id", nullable = false, updatable = false)
    private Long eventSeatId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(nullable = false, updatable = false, length = 64)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TicketStatus status;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "event_title", nullable = false, updatable = false, length = 200)
    private String eventTitle;

    @Column(name = "section_name", nullable = false, updatable = false, length = 100)
    private String sectionName;

    @Column(name = "row_label", nullable = false, updatable = false, length = 20)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false, updatable = false)
    private int seatNumber;

    Ticket(Long orderId, Long eventId, Long eventSeatId, Long customerId, String code, String eventTitle,
           String sectionName, String rowLabel, int seatNumber) {
        this.orderId = orderId;
        this.eventId = eventId;
        this.eventSeatId = eventSeatId;
        this.customerId = customerId;
        this.code = code;
        this.eventTitle = eventTitle;
        this.sectionName = sectionName;
        this.rowLabel = rowLabel;
        this.seatNumber = seatNumber;
        this.status = TicketStatus.VALID;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Ticket other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
