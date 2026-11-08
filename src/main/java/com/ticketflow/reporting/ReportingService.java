package com.ticketflow.reporting;

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
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ReportingService {

    private final ReportingRepository repository;
    private final EventCatalog eventCatalog;

    /** Someone else's event is "not found", like everywhere else. */
    @Transactional(readOnly = true)
    EventStatsResponse eventStats(Long organizerId, Long eventId) {
        if (!eventCatalog.isOrganizer(eventId, organizerId)) {
            throw new ResourceNotFoundException("Event not found.");
        }
        EventRef event = eventCatalog.get(eventId);
        List<SectionStats> sections = repository.sections(eventId).stream()
                .map(s -> new SectionStats(s.sectionId(), s.name(), s.seats(), occupancy(s.seats()), s.revenue()))
                .toList();
        SeatCounts total = new SeatCounts(
                sections.stream().mapToLong(s -> s.seats().total()).sum(),
                sections.stream().mapToLong(s -> s.seats().available()).sum(),
                sections.stream().mapToLong(s -> s.seats().held()).sum(),
                sections.stream().mapToLong(s -> s.seats().sold()).sum());
        ReportingRepository.OrderFigures orders = repository.orders(eventId);
        return new EventStatsResponse(event.id(), event.title(), eventCatalog.statusOf(eventId), total,
                occupancy(total), orders.revenue(), orders.byStatus(), sections);
    }

    @Transactional(readOnly = true)
    OrganizerStatsResponse organizerStats(Long organizerId, Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BadRequestException("The 'from' instant must be before 'to'.");
        }
        List<EventTotals> events = repository.eventTotals(organizerId, from, to).stream()
                .map(e -> new EventTotals(e.eventId(), e.title(), e.status(), e.startsAt(), e.totalSeats(),
                        e.soldSeats(), e.heldSeats(), percent(e.soldSeats(), e.totalSeats()), e.paidOrders(),
                        e.revenue()))
                .toList();
        OrganizerStatsResponse.Totals totals = new OrganizerStatsResponse.Totals(events.size(),
                events.stream().mapToLong(EventTotals::soldSeats).sum(),
                events.stream().mapToLong(EventTotals::paidOrders).sum(),
                events.stream().map(EventTotals::revenue).reduce(BigDecimal.ZERO, BigDecimal::add));
        return new OrganizerStatsResponse(from, to, totals, events);
    }

    private static BigDecimal occupancy(SeatCounts seats) {
        return percent(seats.sold(), seats.total());
    }

    /** sold / total as a percentage with two decimals; 0 when there are no seats (a DRAFT event). */
    static BigDecimal percent(long part, long whole) {
        if (whole == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }
}
