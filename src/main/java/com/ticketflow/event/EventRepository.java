package com.ticketflow.event;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface EventRepository extends JpaRepository<Event, Long> {

    Optional<Event> findByIdAndOrganizerId(Long id, Long organizerId);

    /**
     * Same lookup with {@code SELECT ... FOR UPDATE}. Editing prices and publishing both use it, so they
     * run one after the other for the same event instead of racing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id and e.organizerId = :organizerId")
    Optional<Event> findByIdAndOrganizerIdForUpdate(@Param("id") Long id, @Param("organizerId") Long organizerId);

    Page<Event> findByOrganizerId(Long organizerId, Pageable pageable);

    boolean existsByVenueId(Long venueId);

    /**
     * Creates one AVAILABLE event seat for every seat of the event's venue, with the price category of the
     * seat's section, in a single statement. Returns the number of rows created.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into event_seats (event_id, seat_id, price_category_id, status, version)
            select :eventId, seat.id, pc.id, 'AVAILABLE', 0
            from seats seat
            join sections sec on sec.id = seat.section_id
            join price_categories pc on pc.section_id = sec.id and pc.event_id = :eventId
            where sec.venue_id = :venueId
            """)
    int generateEventSeats(@Param("eventId") Long eventId, @Param("venueId") Long venueId);
}
