package com.ticketflow.venue;

import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.venue.dto.CreateVenueRequest;
import com.ticketflow.venue.dto.SectionResponse;
import com.ticketflow.venue.dto.UpdateVenueRequest;
import com.ticketflow.venue.dto.VenueResponse;
import com.ticketflow.venue.dto.VenueSummary;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Every method takes the id of the calling organizer; a venue of someone else is "not found". */
@Service
@RequiredArgsConstructor
class VenueService {

    private final VenueRepository venueRepository;
    private final VenueMapper venueMapper;

    @Transactional
    VenueResponse create(Long organizerId, CreateVenueRequest request) {
        Venue venue = new Venue(organizerId, request.name().trim(), request.city().trim(),
                request.address().trim());
        VenueLayoutGenerator.generate(venue, request.sections());
        venueRepository.save(venue); // cascades to sections and seats

        // the seats were just created in memory, so there is no need to query their counts
        Map<Long, Long> seatCounts = venue.getSections().stream()
                .collect(Collectors.toMap(Section::getId, section -> (long) section.getSeats().size()));
        return toResponse(venue, seatCounts);
    }

    @Transactional(readOnly = true)
    PageResponse<VenueSummary> list(Long organizerId, Pageable pageable) {
        return PageResponse.from(venueRepository.findByOrganizerId(organizerId, pageable)
                .map(venueMapper::toSummary));
    }

    @Transactional(readOnly = true)
    VenueResponse get(Long organizerId, Long venueId) {
        Venue venue = findOwned(organizerId, venueId);
        Map<Long, Long> seatCounts = venueRepository.countSeatsBySection(venueId).stream()
                .collect(Collectors.toMap(SectionSeatCount::sectionId, SectionSeatCount::seatCount));
        return toResponse(venue, seatCounts);
    }

    @Transactional
    VenueSummary update(Long organizerId, Long venueId, UpdateVenueRequest request) {
        Venue venue = findOwned(organizerId, venueId);
        venue.update(trimOrNull(request.name()), trimOrNull(request.city()), trimOrNull(request.address()));
        return venueMapper.toSummary(venue);
    }

    /**
     * Step 6 adds events that reference venues. Their foreign key already refuses to delete a venue
     * that has events (reported as 409); Step 6 also adds an explicit check with a clear message.
     */
    @Transactional
    void delete(Long organizerId, Long venueId) {
        venueRepository.delete(findOwned(organizerId, venueId));
    }

    private Venue findOwned(Long organizerId, Long venueId) {
        return venueRepository.findByIdAndOrganizerId(venueId, organizerId)
                .orElseThrow(() -> new ResourceNotFoundException("Venue not found."));
    }

    private VenueResponse toResponse(Venue venue, Map<Long, Long> seatCounts) {
        List<SectionResponse> sections = venue.getSections().stream()
                .map(section -> venueMapper.toSectionResponse(section, seatCounts))
                .toList();
        long totalSeats = sections.stream().mapToLong(SectionResponse::seatCount).sum();
        return new VenueResponse(venue.getId(), venue.getName(), venue.getCity(), venue.getAddress(), sections,
                totalSeats);
    }

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }
}
