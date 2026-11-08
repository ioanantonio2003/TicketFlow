package com.ticketflow.venue;

/** Result row of the seat count query: how many seats a section has. */
record SectionSeatCount(Long sectionId, long seatCount) {
}
