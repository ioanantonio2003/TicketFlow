package com.ticketflow.notification;

import com.ticketflow.booking.OrderCancelled;
import com.ticketflow.booking.OrderRefunded;
import com.ticketflow.event.EventCatalog;
import com.ticketflow.ticket.TicketsIssued;
import com.ticketflow.user.UserService;
import com.ticketflow.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns business events into emails, after the transaction that produced them committed (so nobody is told about
 * something that was rolled back). The paid-order email is triggered by {@code TicketsIssued} rather than
 * {@code OrderPaid}, because it contains the ticket codes and those exist only once the tickets were issued.
 * Nothing here may fail the caller: every handler catches its own errors.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class NotificationListener {

    private final EmailService emailService;
    private final UserService userService;
    private final EventCatalog eventCatalog;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onTicketsIssued(TicketsIssued issued) {
        notify("tickets of order " + issued.orderId(), () -> {
            UserResponse user = userService.findById(issued.customerId());
            emailService.send(EmailTemplates.ticketsIssued(user.email(), user.displayName(), issued.eventTitle(),
                    issued.orderId(), issued.tickets()));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onOrderRefunded(OrderRefunded refunded) {
        notify("refund of order " + refunded.orderId(), () -> {
            UserResponse user = userService.findById(refunded.customerId());
            emailService.send(EmailTemplates.orderRefunded(user.email(), user.displayName(),
                    eventCatalog.get(refunded.eventId()).title(), refunded.orderId(), refunded.amount(),
                    refunded.reason()));
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onOrderCancelled(OrderCancelled cancelled) {
        notify("cancellation of order " + cancelled.orderId(), () -> {
            UserResponse user = userService.findById(cancelled.customerId());
            emailService.send(EmailTemplates.orderCancelled(user.email(), user.displayName(),
                    eventCatalog.get(cancelled.eventId()).title(), cancelled.orderId(), cancelled.reason()));
        });
    }

    private void notify(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Could not prepare the email for {}", what, e);
        }
    }
}
