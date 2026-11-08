package com.ticketflow.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.booking.OrderStatus;
import com.ticketflow.common.exception.BadRequestException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.event.EventCatalog;
import com.ticketflow.event.dto.EventRef;
import com.ticketflow.reporting.dto.EventStatsResponse;
import com.ticketflow.reporting.dto.EventTotals;
import com.ticketflow.reporting.dto.OrganizerStatsResponse;
import com.ticketflow.reporting.dto.SeatCounts;
import com.ticketflow.reporting.dto.SectionStats;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReportingServiceTest {

    private static final Instant START = Instant.parse("2030-03-01T18:00:00Z");

    @Mock
    private ReportingRepository repository;
    @Mock
    private EventCatalog eventCatalog;

    private ReportingService service;

    @BeforeEach
    void setUp() {
        service = new ReportingService(repository, eventCatalog);
    }

    @Test
    void anotherOrganizersEventIsNotFoundAndNothingIsQueried() {
        when(eventCatalog.isOrganizer(3L, 7L)).thenReturn(false);

        assertThatThrownBy(() -> service.eventStats(7L, 3L)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void theEventTotalsAreTheSumOfTheSectionsAndOccupancyIsSoldOverTotal() {
        when(eventCatalog.isOrganizer(3L, 7L)).thenReturn(true);
        when(eventCatalog.get(3L)).thenReturn(new EventRef(3L, "Concert", START));
        when(eventCatalog.statusOf(3L)).thenReturn("PUBLISHED");
        when(repository.sections(3L)).thenReturn(List.of(
                new SectionStats(1L, "Floor", new SeatCounts(6, 3, 1, 2), null, new BigDecimal("20.00")),
                new SectionStats(2L, "Balcony", new SeatCounts(4, 3, 0, 1), null, new BigDecimal("30.00"))));
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        byStatus.put(OrderStatus.PAID, 2L);
        when(repository.orders(3L)).thenReturn(new ReportingRepository.OrderFigures(byStatus, new BigDecimal("50.00")));

        EventStatsResponse stats = service.eventStats(7L, 3L);

        assertThat(stats.seats()).isEqualTo(new SeatCounts(10, 6, 1, 3));
        assertThat(stats.occupancyPercent()).isEqualByComparingTo("30.00");
        assertThat(stats.revenue()).isEqualByComparingTo("50.00");
        assertThat(stats.sections()).extracting(SectionStats::occupancyPercent)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("33.33"), new BigDecimal("25.00"));   // 2/6 and 1/4
        assertThat(stats.status()).isEqualTo("PUBLISHED");
    }

    @Test
    void anEventWithoutSeatsHasZeroOccupancyNotADivisionByZero() {
        assertThat(ReportingService.percent(0, 0)).isEqualByComparingTo("0");
        assertThat(ReportingService.percent(1, 3)).isEqualByComparingTo("33.33");
        assertThat(ReportingService.percent(2, 3)).isEqualByComparingTo("66.67");   // rounded half up
        assertThat(ReportingService.percent(10, 10)).isEqualByComparingTo("100.00");
    }

    @Test
    void organizerTotalsAddUpTheListedEvents() {
        when(repository.eventTotals(7L, null, null)).thenReturn(List.of(
                new EventTotals(1L, "A", "PUBLISHED", START, 10, 3, 1, null, 2, new BigDecimal("50.00")),
                new EventTotals(2L, "B", "CANCELLED", START, 4, 0, 0, null, 0, BigDecimal.ZERO)));

        OrganizerStatsResponse stats = service.organizerStats(7L, null, null);

        assertThat(stats.totals().events()).isEqualTo(2);
        assertThat(stats.totals().soldSeats()).isEqualTo(3);
        assertThat(stats.totals().paidOrders()).isEqualTo(2);
        assertThat(stats.totals().revenue()).isEqualByComparingTo("50.00");
        assertThat(stats.events().get(0).occupancyPercent()).isEqualByComparingTo("30.00");
        assertThat(stats.events().get(1).occupancyPercent()).isEqualByComparingTo("0");
    }

    @Test
    void anEmptyRangeGivesZeroTotals() {
        when(repository.eventTotals(7L, null, null)).thenReturn(List.of());

        OrganizerStatsResponse stats = service.organizerStats(7L, null, null);

        assertThat(stats.events()).isEmpty();
        assertThat(stats.totals().revenue()).isEqualByComparingTo("0");
    }

    @Test
    void aRangeMustBeOrdered() {
        Instant a = START;
        Instant b = START.plusSeconds(60);

        assertThatThrownBy(() -> service.organizerStats(7L, b, a)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.organizerStats(7L, a, a)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository);
    }
}
