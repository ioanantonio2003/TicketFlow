package com.ticketflow.event;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import com.ticketflow.venue.Venue;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An event in a venue. The state machine lives here: invalid transitions throw a BusinessRuleException
 * (422). The current time is passed in by the service (from the Clock), never read here.
 */
@Entity
@Table(name = "events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class Event extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organizer_id", nullable = false, updatable = false)
    private Long organizerId;

    /** Plain id: the event feature talks to the venue feature through VenueCatalog, not its entities. */
    @Column(name = "venue_id", nullable = false, updatable = false)
    private Long venueId;

    /**
     * Read-only association used by the public search to filter and show the venue's city and name in
     * the same query. Writes go through {@code venueId}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", insertable = false, updatable = false)
    private Venue venue;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 5000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventCategory category;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    @Version
    private Long version;

    private Event(Long organizerId, Long venueId, String title, String description, EventCategory category,
                  Instant startsAt) {
        this.organizerId = organizerId;
        this.venueId = venueId;
        this.title = title;
        this.description = description;
        this.category = category;
        this.startsAt = startsAt;
        this.status = EventStatus.DRAFT;
    }

    static Event draft(Long organizerId, Long venueId, String title, String description, EventCategory category,
                       Instant startsAt, Instant now) {
        requireFuture(startsAt, now);
        return new Event(organizerId, venueId, title, description, category, startsAt);
    }

    /** Only a DRAFT may be edited (this includes its prices). */
    void assertEditable() {
        if (status != EventStatus.DRAFT) {
            throw new BusinessRuleException("Only a DRAFT event can be edited (current status: " + status + ").");
        }
    }

    void update(String title, String description, EventCategory category, Instant startsAt, Instant now) {
        assertEditable();
        requireFuture(startsAt, now);
        this.title = title;
        this.description = description;
        this.category = category;
        this.startsAt = startsAt;
    }

    /** The caller must have checked that every section has a price and must create the event seats. */
    void publish(Instant now) {
        if (status != EventStatus.DRAFT) {
            throw new BusinessRuleException("Only a DRAFT event can be published (current status: " + status + ").");
        }
        requireFuture(startsAt, now);
        this.status = EventStatus.PUBLISHED;
    }

    void cancel() {
        if (status != EventStatus.DRAFT && status != EventStatus.PUBLISHED) {
            throw new BusinessRuleException(
                    "Only a DRAFT or PUBLISHED event can be cancelled (current status: " + status + ").");
        }
        this.status = EventStatus.CANCELLED;
    }

    private static void requireFuture(Instant startsAt, Instant now) {
        if (!startsAt.isAfter(now)) {
            throw new BusinessRuleException("The event must start in the future.");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Event other && id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
