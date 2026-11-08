package com.ticketflow.venue;

import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.venue.dto.SectionRef;
import com.ticketflow.venue.dto.VenueRef;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public read-only entry point of the venue feature for other features (events). */
@Service
@RequiredArgsConstructor
public class VenueCatalog {

    private final VenueRepository venueRepository;

    /** Same rule as everywhere: someone else's venue is "not found". */
    @Transactional(readOnly = true)
    public VenueRef requireOwned(Long venueId, Long organizerId) {
        return venueRepository.findByIdAndOrganizerId(venueId, organizerId)
                .map(v -> new VenueRef(v.getId(), v.getName(), v.getCity()))
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found."));
    }

    @Transactional(readOnly = true)
    public List<SectionRef> sections(Long venueId) {
        return venueRepository.findSectionRefs(venueId);
    }

    @Transactional(readOnly = true)
    public long seatCount(Long venueId) {
        return venueRepository.countSeatsByVenue(venueId);
    }
}
