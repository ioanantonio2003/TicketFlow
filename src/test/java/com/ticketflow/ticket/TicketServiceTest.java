package com.ticketflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketflow.booking.OrderPaid;
import com.ticketflow.booking.OrderSeats;
import com.ticketflow.booking.dto.OrderItemResponse;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.event.EventCatalog;
import com.ticketflow.event.dto.EventRef;
import com.ticketflow.ticket.dto.TicketValidationResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final OrderPaid PAID = new OrderPaid(55L, 7L, 3L);

    @Mock
    private TicketRepository ticketRepository;
    @Mock
    private TicketCodeGenerator codeGenerator;
    @Mock
    private QrCodeGenerator qrCodeGenerator;
    @Mock
    private OrderSeats orderSeats;
    @Mock
    private EventCatalog eventCatalog;
    @Mock
    private ApplicationEventPublisher events;

    private TicketService service;

    @BeforeEach
    void setUp() {
        service = new TicketService(ticketRepository, codeGenerator, qrCodeGenerator, orderSeats, eventCatalog,
                Clock.fixed(NOW, ZoneOffset.UTC), events);
    }

    private static Ticket ticket(long id, long eventId, String code) {
        Ticket ticket = new Ticket(55L, eventId, 100L, 7L, code, "Concert", "Floor", "1", 4);
        ReflectionTestUtils.setField(ticket, "id", id);
        return ticket;
    }

    // ---- issuing ----

    @Test
    void issuesOneTicketPerSeatWithItsOwnCode() {
        when(ticketRepository.existsByOrderId(55L)).thenReturn(false);
        when(eventCatalog.get(3L)).thenReturn(new EventRef(3L, "Concert", NOW.plusSeconds(999)));
        when(orderSeats.of(55L)).thenReturn(List.of(
                new OrderItemResponse(10L, "Floor", "1", 1, new BigDecimal("20.00")),
                new OrderItemResponse(11L, "Balcony", "B", 7, new BigDecimal("30.00"))));
        when(codeGenerator.next()).thenReturn("code-a", "code-b");

        int created = service.issue(PAID);

        assertThat(created).isEqualTo(2);
        ArgumentCaptor<Ticket> saved = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(Ticket::getCode).containsExactly("code-a", "code-b");
        assertThat(saved.getAllValues()).extracting(Ticket::getEventSeatId).containsExactly(10L, 11L);
        assertThat(saved.getAllValues()).extracting(Ticket::getSectionName).containsExactly("Floor", "Balcony");
        verify(events).publishEvent(new TicketsIssued(55L, 7L, 3L, "Concert", List.of(
                new TicketsIssued.IssuedTicket("Floor", "1", 1, "code-a"),
                new TicketsIssued.IssuedTicket("Balcony", "B", 7, "code-b"))));
        assertThat(saved.getAllValues()).allSatisfy(t -> {
            assertThat(t.getStatus()).isEqualTo(TicketStatus.VALID);
            assertThat(t.getCustomerId()).isEqualTo(7L);
            assertThat(t.getOrderId()).isEqualTo(55L);
            assertThat(t.getEventTitle()).isEqualTo("Concert");
        });
    }

    @Test
    void issuingTwiceForTheSameOrderCreatesNothingTheSecondTime() {
        when(ticketRepository.existsByOrderId(55L)).thenReturn(true);

        assertThat(service.issue(PAID)).isZero();

        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(orderSeats, eventCatalog, events);
    }

    // ---- ownership ----

    @Test
    void someoneElsesTicketIsNotFoundForReadAndForQr() {
        when(ticketRepository.findByIdAndCustomerId(9L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(7L, 9L)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.qr(7L, 9L)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(qrCodeGenerator);
    }

    @Test
    void theQrContainsTheTicketCode() {
        when(ticketRepository.findByIdAndCustomerId(9L, 7L)).thenReturn(Optional.of(ticket(9L, 3L, "the-code")));
        when(qrCodeGenerator.png("the-code")).thenReturn(new byte[] {1, 2, 3});

        assertThat(service.qr(7L, 9L)).containsExactly(1, 2, 3);
    }

    // ---- validation ----

    @Test
    void theOrganizerOfTheEventCanScanAValidTicketOnce() {
        when(ticketRepository.findByCode("c")).thenReturn(Optional.of(ticket(9L, 3L, "c")));
        when(eventCatalog.isOrganizer(3L, 20L)).thenReturn(true);
        when(ticketRepository.markUsed(9L, NOW)).thenReturn(1);

        TicketValidationResponse response = service.validate(20L, "c");

        assertThat(response.ticketId()).isEqualTo(9L);
        assertThat(response.usedAt()).isEqualTo(NOW);
        assertThat(response.section()).isEqualTo("Floor");
    }

    @Test
    void aSecondScanIsAConflict() {
        when(ticketRepository.findByCode("c")).thenReturn(Optional.of(ticket(9L, 3L, "c")));
        when(eventCatalog.isOrganizer(3L, 20L)).thenReturn(true);
        when(ticketRepository.markUsed(9L, NOW)).thenReturn(0);
        when(ticketRepository.findStatusById(9L)).thenReturn(TicketStatus.USED);

        assertThatThrownBy(() -> service.validate(20L, "c")).isInstanceOf(ConflictException.class)
                .hasMessage("This ticket has already been used.");
    }

    @Test
    void aCancelledTicketIsRefusedWithAnotherMessage() {
        when(ticketRepository.findByCode("c")).thenReturn(Optional.of(ticket(9L, 3L, "c")));
        when(eventCatalog.isOrganizer(3L, 20L)).thenReturn(true);
        when(ticketRepository.markUsed(9L, NOW)).thenReturn(0);
        when(ticketRepository.findStatusById(9L)).thenReturn(TicketStatus.CANCELLED);

        assertThatThrownBy(() -> service.validate(20L, "c")).isInstanceOf(ConflictException.class)
                .hasMessage("This ticket is not valid.");
    }

    @Test
    void anotherOrganizersTicketLooksLikeAnUnknownCodeAndIsNotConsumed() {
        when(ticketRepository.findByCode("c")).thenReturn(Optional.of(ticket(9L, 3L, "c")));
        when(eventCatalog.isOrganizer(3L, 21L)).thenReturn(false);

        assertThatThrownBy(() -> service.validate(21L, "c")).isInstanceOf(ResourceNotFoundException.class);
        verify(ticketRepository, never()).markUsed(any(), any());
    }

    @Test
    void anUnknownCodeIsNotFound() {
        when(ticketRepository.findByCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate(20L, "nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void refundingCancelsTheNotYetUsedTicketsOfTheOrder() {
        service.cancelValidTicketsOf(55L);

        verify(ticketRepository).cancelValidOfOrder(55L, NOW);
    }
}
