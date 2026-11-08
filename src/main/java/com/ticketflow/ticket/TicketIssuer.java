package com.ticketflow.ticket;

import com.ticketflow.booking.OrderPaid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Creates the tickets of an order after its payment was committed. The ticket feature never touches the
 * payment transaction: if issuing fails the customer's payment is still valid, the failure is logged, and
 * running {@link TicketService#issue} again for the order is safe. (A durable redelivery comes with the
 * transactional outbox, Step 18.)
 *
 * <p>{@code issue} starts its own new transaction (REQUIRES_NEW); the payment one is already finished here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TicketIssuer {

    private final TicketService ticketService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onOrderPaid(OrderPaid paid) {
        try {
            int created = ticketService.issue(paid);
            log.info("Issued {} ticket(s) for order {}", created, paid.orderId());
        } catch (RuntimeException e) {
            log.error("Could not issue tickets for paid order {}", paid.orderId(), e);
        }
    }
}
