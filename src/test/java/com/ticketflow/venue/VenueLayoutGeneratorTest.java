package com.ticketflow.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.venue.dto.RowRequest;
import com.ticketflow.venue.dto.SectionRequest;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VenueLayoutGeneratorTest {

    private final Venue venue = new Venue(1L, "Arena", "Cluj", "Main street 1");

    private static SectionRequest section(String name, RowRequest... rows) {
        return new SectionRequest(name, List.of(rows));
    }

    private static RowRequest row(String label, int seats) {
        return new RowRequest(label, seats);
    }

    @Test
    void threeSectionsOfTenRowsWithTwentySeatsGive600Seats() {
        List<SectionRequest> sections = List.of("A", "B", "C").stream()
                .map(name -> new SectionRequest(name,
                        IntStream.rangeClosed(1, 10).mapToObj(i -> row(String.valueOf(i), 20)).toList()))
                .toList();

        VenueLayoutGenerator.generate(venue, sections);

        assertThat(venue.getSections()).hasSize(3);
        assertThat(venue.getSections()).allSatisfy(s -> assertThat(s.getSeats()).hasSize(200));
        assertThat(venue.getSections()).extracting(Section::getName).containsExactly("A", "B", "C");
        assertThat(venue.getSections()).extracting(Section::getSortOrder).containsExactly(0, 1, 2);
    }

    @Test
    void seatsAreNumberedFromOneInEachRow() {
        VenueLayoutGenerator.generate(venue, List.of(section("Balcony", row("A", 3), row("B", 2))));

        List<Seat> seats = venue.getSections().get(0).getSeats();
        assertThat(seats).extracting(s -> s.getRowLabel() + s.getSeatNumber())
                .containsExactly("A1", "A2", "A3", "B1", "B2");
    }

    @Test
    void namesAndLabelsAreTrimmed() {
        VenueLayoutGenerator.generate(venue, List.of(section("  Stalls ", row(" 1 ", 1))));

        assertThat(venue.getSections().get(0).getName()).isEqualTo("Stalls");
        assertThat(venue.getSections().get(0).getSeats().get(0).getRowLabel()).isEqualTo("1");
    }

    @Test
    void exactlyTheMaximumNumberOfSeatsIsAllowed() {
        List<SectionRequest> sections = List.of(
                section("A", row("1", 1000), row("2", 1000), row("3", 1000), row("4", 1000), row("5", 1000)));

        VenueLayoutGenerator.generate(venue, sections);

        assertThat(venue.getSections().get(0).getSeats()).hasSize(VenueLayoutGenerator.MAX_SEATS_PER_VENUE);
    }

    @Test
    void moreThanTheMaximumNumberOfSeatsIsRejectedAcrossSections() {
        List<SectionRequest> sections = List.of(
                section("A", row("1", 1000), row("2", 1000), row("3", 1000)),
                section("B", row("1", 1000), row("2", 1000), row("3", 1)));

        assertThatThrownBy(() -> VenueLayoutGenerator.generate(venue, sections))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("5000");
        assertThat(venue.getSections()).isEmpty(); // rejected before anything was created
    }

    @Test
    void duplicateSectionNamesAreRejectedIgnoringCase() {
        List<SectionRequest> sections = List.of(section("Floor", row("1", 1)), section("floor ", row("1", 1)));

        assertThatThrownBy(() -> VenueLayoutGenerator.generate(venue, sections))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Duplicate section");
    }

    @Test
    void duplicateRowLabelsInOneSectionAreRejected() {
        List<SectionRequest> sections = List.of(section("Floor", row("A", 5), row("a", 5)));

        assertThatThrownBy(() -> VenueLayoutGenerator.generate(venue, sections))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Duplicate row");
    }

    @Test
    void sameRowLabelInDifferentSectionsIsFine() {
        VenueLayoutGenerator.generate(venue, List.of(section("A", row("1", 2)), section("B", row("1", 2))));

        assertThat(venue.getSections()).hasSize(2);
    }
}
