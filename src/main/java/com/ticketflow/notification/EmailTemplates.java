package com.ticketflow.notification;

import com.ticketflow.booking.CancellationReason;
import com.ticketflow.ticket.TicketsIssued.IssuedTicket;
import java.math.BigDecimal;
import java.util.List;

/**
 * The texts of the emails. Plain text with {@code String.formatted}: nothing to escape, nothing to configure, and
 * easy to read in a diff. Each method returns subject and body for one situation.
 */
final class EmailTemplates {

    private EmailTemplates() {
    }

    static EmailMessage ticketsIssued(String to, String name, String eventTitle, Long orderId,
                                      List<IssuedTicket> tickets) {
        StringBuilder lines = new StringBuilder();
        for (IssuedTicket ticket : tickets) {
            lines.append("  - %s, row %s, seat %d: %s%n".formatted(ticket.section(), ticket.row(),
                    ticket.seatNumber(), ticket.code()));
        }
        String body = """
                Hi %s,

                Your payment for order #%d was received. Here are your tickets for "%s":

                %s
                Show the QR code of each ticket at the entrance. You can download it from the app.

                See you there!
                TicketFlow
                """.formatted(name, orderId, eventTitle, lines);
        return new EmailMessage(to, "Your tickets for " + eventTitle, body);
    }

    static EmailMessage orderRefunded(String to, String name, String eventTitle, Long orderId, BigDecimal amount,
                                      CancellationReason reason) {
        String why = reason == CancellationReason.EVENT_CANCELLED
                ? "The event \"%s\" was cancelled by the organizer.".formatted(eventTitle)
                : "You asked for a refund of your order for \"%s\".".formatted(eventTitle);
        String body = """
                Hi %s,

                %s
                Order #%d was refunded: %s RON is on its way back to you, and the tickets are no longer valid.

                TicketFlow
                """.formatted(name, why, orderId, amount.toPlainString());
        String subject = reason == CancellationReason.EVENT_CANCELLED
                ? "Event cancelled: " + eventTitle : "Refund for " + eventTitle;
        return new EmailMessage(to, subject, body);
    }

    static EmailMessage orderCancelled(String to, String name, String eventTitle, Long orderId,
                                       CancellationReason reason) {
        String why = reason == CancellationReason.EVENT_CANCELLED
                ? "The event \"%s\" was cancelled by the organizer.".formatted(eventTitle)
                : "You cancelled your order for \"%s\".".formatted(eventTitle);
        String body = """
                Hi %s,

                %s
                Order #%d was cancelled and your seats were released. You have not been charged.

                TicketFlow
                """.formatted(name, why, orderId);
        String subject = reason == CancellationReason.EVENT_CANCELLED
                ? "Event cancelled: " + eventTitle : "Order cancelled: " + eventTitle;
        return new EmailMessage(to, subject, body);
    }
}
