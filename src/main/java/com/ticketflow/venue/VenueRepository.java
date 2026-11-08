package com.ticketflow.venue;

import com.ticketflow.venue.dto.SectionRef;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface VenueRepository extends JpaRepository<Venue, Long> {

    /** Ownership lives in the query: someone else's venue is simply "not found". */
    Optional<Venue> findByIdAndOrganizerId(Long id, Long organizerId);

    Page<Venue> findByOrganizerId(Long organizerId, Pageable pageable);

    /** One grouped query instead of loading every seat to count them. */
    @Query("""
            select new com.ticketflow.venue.SectionSeatCount(s.section.id, count(s))
            from Seat s
            where s.section.venue.id = :venueId
            group by s.section.id
            """)
    List<SectionSeatCount> countSeatsBySection(@Param("venueId") Long venueId);

    @Query("""
            select new com.ticketflow.venue.dto.SectionRef(s.id, s.name)
            from Section s
            where s.venue.id = :venueId
            order by s.sortOrder, s.id
            """)
    List<SectionRef> findSectionRefs(@Param("venueId") Long venueId);

    @Query("select count(s) from Seat s where s.section.venue.id = :venueId")
    long countSeatsByVenue(@Param("venueId") Long venueId);
}
