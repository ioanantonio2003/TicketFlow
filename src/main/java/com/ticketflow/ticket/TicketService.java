package com.ticketflow.ticket;

import com.ticketflow.booking.OrderPaid;
import com.ticketflow.booking.OrderSeats;
import com.ticketflow.booking.dto.OrderItemResponse;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.event.EventCatalog;
import com.ticketflow.ticket.dto.TicketResponse;
import com.ticketflow.ticket.dto.TicketValidationResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
class TicketService {

    private final TicketRepository ticketRepository;
    private final TicketCodeGenerator codeGenerator;
    private final QrCodeGenerator qrCodeGenerator;
    private final OrderSeats orderSeats;
    private final EventCatalog eventCatalog;
    private final Clock clock;

    /**
     * One ticket per seat of the paid order. Safe to call twice for the same order (a redelivered event): if the
     * order already has tickets nothing is created, and the partial unique index on the seat is the last guard.
     *
     * <p>REQUIRES_NEW is essential: it runs from an AFTER_COMMIT callback, where the payment transaction is already
     * committed but still bound to the thread. A plain {@code @Transactional} would join that finished transaction
     * and the tickets would never be committed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    int issue(OrderPaid paid) {
        if (ticketRepository.existsByOrderId(paid.orderId())) {
            log.info("Order {} already has tickets: nothing to issue", paid.orderId());
            return 0;
        }
        String eventTitle = eventCatalog.get(paid.eventId()).title();
        List<OrderItemResponse> seats = orderSeats.of(paid.orderId());
        for (OrderItemResponse seat : seats) {
            ticketRepository.save(new Ticket(paid.orderId(), paid.eventId(), seat.eventSeatId(), paid.customerId(),
                    codeGenerator.next(), eventTitle, seat.section(), seat.row(), seat.seatNumber()));
        }
        return seats.size();
    }

    @Transactional(readOnly = true)
    PageResponse<TicketResponse> listMine(Long customerId, Pageable pageable) {
        return PageResponse.from(ticketRepository.findByCustomerId(customerId, pageable).map(TicketService::toResponse));
    }

    @Transactional(readOnly = true)
    TicketResponse get(Long customerId, Long ticketId) {
        return toResponse(owned(customerId, ticketId));
    }

    @Transactional(readOnly = true)
    byte[] qr(Long customerId, Long ticketId) {
        return qrCodeGenerator.png(owned(customerId, ticketId).getCode());
    }

    /**
     * Scans a ticket at the entrance. A code that does not exist and a code of an event the caller does not
     * organize look the same (404), so an organizer cannot probe other events. The VALID -> USED change is one
     * conditional UPDATE: with two simultaneous scans exactly one gets 1 row and the other a 409.
     */
    @Transactional
    TicketValidationResponse validate(Long organizerId, String code) {
        Ticket ticket = ticketRepository.findByCode(code)
                .filter(t -> eventCatalog.isOrganizer(t.getEventId(), organizerId))
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found."));
        Instant now = clock.instant();
        if (ticketRepository.markUsed(ticket.getId(), now) == 0) {
            throw new ConflictException(currentStatusMessage(ticket.getId()));
        }
        return new TicketValidationResponse(ticket.getId(), ticket.getEventId(), ticket.getEventTitle(),
                ticket.getSectionName(), ticket.getRowLabel(), ticket.getSeatNumber(), now);
    }

    private String currentStatusMessage(Long ticketId) {
        // the bulk UPDATE bypasses the persistence context, so read the status again from the database
        TicketStatus status = ticketRepository.findStatusById(ticketId);
        return status == TicketStatus.USED ? "This ticket has already been used." : "This ticket is not valid.";
    }

    private Ticket owned(Long customerId, Long ticketId) {
        return ticketRepository.findByIdAndCustomerId(ticketId, customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket not found."));
    }

    private static TicketResponse toResponse(Ticket t) {
        return new TicketResponse(t.getId(), t.getOrderId(), t.getEventId(), t.getEventTitle(), t.getSectionName(),
                t.getRowLabel(), t.getSeatNumber(), t.getStatus(), t.getCode(), t.getUsedAt());
    }
}
