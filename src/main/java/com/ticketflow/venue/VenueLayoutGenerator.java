package com.ticketflow.venue;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.venue.dto.RowRequest;
import com.ticketflow.venue.dto.SectionRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns the described layout (sections, rows, seat counts) into sections and seats of a venue and
 * enforces the rules the bean validation annotations cannot express: no duplicate names and the
 * total seat limit. Pure logic, no Spring, so it is easy to unit test.
 */
final class VenueLayoutGenerator {

    static final int MAX_SEATS_PER_VENUE = 5000;

    private VenueLayoutGenerator() {
    }

    static void generate(Venue venue, List<SectionRequest> sectionRequests) {
        assertTotalSeatsWithinLimit(sectionRequests);

        Set<String> sectionNames = new HashSet<>();
        for (SectionRequest sectionRequest : sectionRequests) {
            String sectionName = sectionRequest.name().trim();
            if (!sectionNames.add(sectionName.toLowerCase())) {
                throw new BusinessRuleException("Duplicate section name: " + sectionName);
            }
            Section section = venue.addSection(sectionName);

            Set<String> rowLabels = new HashSet<>();
            for (RowRequest row : sectionRequest.rows()) {
                String label = row.label().trim();
                if (!rowLabels.add(label.toLowerCase())) {
                    throw new BusinessRuleException(
                            "Duplicate row label '" + label + "' in section " + sectionName);
                }
                section.addRow(label, row.seats());
            }
        }
    }

    // Checked before creating any entity, so a huge request does not allocate thousands of objects first.
    private static void assertTotalSeatsWithinLimit(List<SectionRequest> sectionRequests) {
        long total = sectionRequests.stream()
                .flatMap(section -> section.rows().stream())
                .mapToLong(RowRequest::seats)
                .sum();
        if (total > MAX_SEATS_PER_VENUE) {
            throw new BusinessRuleException(
                    "A venue can have at most " + MAX_SEATS_PER_VENUE + " seats (requested " + total + ").");
        }
    }
}
