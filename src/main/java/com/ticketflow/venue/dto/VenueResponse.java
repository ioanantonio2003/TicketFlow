package com.ticketflow.venue.dto;

import java.util.List;

public record VenueResponse(Long id, String name, String city, String address, List<SectionResponse> sections,
                            long totalSeats) {
}
