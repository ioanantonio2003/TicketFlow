package com.ticketflow.venue;

import com.ticketflow.venue.dto.SectionResponse;
import com.ticketflow.venue.dto.VenueSummary;
import java.util.Map;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
interface VenueMapper {

    VenueSummary toSummary(Venue venue);

    /** The seat count is not a field of Section, so it is looked up in the counts passed by the service. */
    @Mapping(target = "seatCount", expression = "java(seatCounts.getOrDefault(section.getId(), 0L))")
    SectionResponse toSectionResponse(Section section, @Context Map<Long, Long> seatCounts);
}
