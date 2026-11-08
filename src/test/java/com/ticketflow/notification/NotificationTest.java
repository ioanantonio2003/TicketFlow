package com.ticketflow.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.booking.CancellationReason;
import com.ticketflow.booking.OrderCancelled;
import com.ticketflow.booking.OrderRefunded;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.event.EventCatalog;
import com.ticketflow.event.dto.EventRef;
import com.ticketflow.ticket.TicketsIssued;
import com.ticketflow.ticket.TicketsIssued.IssuedTicket;
import com.ticketflow.user.Role;
import com.ticketflow.user.UserService;
import com.ticketflow.user.dto.UserResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class NotificationTest {

    private final EmailService emailService = mock(EmailService.class);
    private final UserService userService = mock(UserService.class);
    private final EventCatalog eventCatalog = mock(EventCatalog.class);
    private final NotificationListener listener = new NotificationListener(emailService, userService, eventCatalog);

    @BeforeEach
    void setUp() {
        when(userService.findById(7L)).thenReturn(new UserResponse(7L, "ana@example.com", "Ana", Role.CUSTOMER));
        when(eventCatalog.get(3L)).thenReturn(new EventRef(3L, "Rock Night", Instant.parse("2030-02-01T18:00:00Z")));
    }

    private EmailMessage sent() {
        ArgumentCaptor<EmailMessage> captor = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).send(captor.capture());
        return captor.getValue();
    }

    // ---- which email for which event ----

    @Test
    void paidOrderEmailListsEveryTicketCode() {
        listener.onTicketsIssued(new TicketsIssued(55L, 7L, 3L, "Rock Night", List.of(
                new IssuedTicket("Floor", "1", 4, "CODE-AAAA"), new IssuedTicket("Floor", "1", 5, "CODE-BBBB"))));

        EmailMessage email = sent();
        assertThat(email.to()).isEqualTo("ana@example.com");
        assertThat(email.subject()).isEqualTo("Your tickets for Rock Night");
        assertThat(email.body()).contains("Hi Ana", "order #55", "Floor, row 1, seat 4: CODE-AAAA",
                "Floor, row 1, seat 5: CODE-BBBB");
    }

    @Test
    void refundEmailSaysWhyAndHowMuch() {
        listener.onOrderRefunded(new OrderRefunded(55L, 7L, 3L, new BigDecimal("75.00"),
                CancellationReason.CUSTOMER_REQUEST));

        EmailMessage email = sent();
        assertThat(email.subject()).isEqualTo("Refund for Rock Night");
        assertThat(email.body()).contains("You asked for a refund", "75.00 RON", "no longer valid");
    }

    @Test
    void refundBecauseOfACancelledEventSaysTheEventWasCancelled() {
        listener.onOrderRefunded(new OrderRefunded(55L, 7L, 3L, new BigDecimal("75.00"),
                CancellationReason.EVENT_CANCELLED));

        EmailMessage email = sent();
        assertThat(email.subject()).isEqualTo("Event cancelled: Rock Night");
        assertThat(email.body()).contains("was cancelled by the organizer");
    }

    @Test
    void cancelledOrderEmailsDependOnTheReason() {
        listener.onOrderCancelled(new OrderCancelled(55L, 7L, 3L, CancellationReason.EVENT_CANCELLED));

        EmailMessage email = sent();
        assertThat(email.subject()).isEqualTo("Event cancelled: Rock Night");
        assertThat(email.body()).contains("You have not been charged");
        assertThat(EmailTemplates.orderCancelled("a@b.c", "Ana", "Rock Night", 55L, CancellationReason.CUSTOMER_REQUEST)
                .body()).contains("You cancelled your order");
    }

    @Test
    void aProblemPreparingAnEmailNeverReachesTheCaller() {
        when(userService.findById(7L)).thenThrow(new ResourceNotFoundException("User not found."));

        listener.onOrderCancelled(new OrderCancelled(55L, 7L, 3L, CancellationReason.CUSTOMER_REQUEST));

        verify(emailService, never()).send(any());
    }

    // ---- sending ----

    @Test
    void disabledNotificationsSendNothing() {
        JavaMailSender mailSender = mock(JavaMailSender.class);

        new EmailService(mailSender, new NotificationProperties("noreply@x", false))
                .send(new EmailMessage("a@b.c", "Subject", "Body"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void aMailServerErrorIsSwallowedAfterLogging() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        new EmailService(mailSender, new NotificationProperties("noreply@x", true))
                .send(new EmailMessage("a@b.c", "Subject", "Body")); // must not throw

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getFrom()).isEqualTo("noreply@x");
        assertThat(sent.getValue().getTo()).containsExactly("a@b.c");
    }
}
