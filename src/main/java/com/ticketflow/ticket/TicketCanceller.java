package com.ticketflow.ticket;

import com.ticketflow.booking.OrderRefunded;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A refunded order must not keep valid tickets. This is a plain {@code @EventListener} on purpose: it runs
 * synchronously inside the refund transaction, so the order, the seats and the tickets change together or not at
 * all. (An AFTER_COMMIT listener would leave a window in which a refunded ticket still scans.)
 */
@Component
@RequiredArgsConstructor
class TicketCanceller {

    private final TicketService ticketService;

    @EventListener
    void onOrderRefunded(OrderRefunded refunded) {
        ticketService.cancelValidTicketsOf(refunded.orderId());
    }
}
