package com.ticketflow.ticket;

import com.ticketflow.common.web.CurrentUser;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.ticket.dto.TicketResponse;
import com.ticketflow.ticket.dto.TicketValidationResponse;
import com.ticketflow.ticket.dto.ValidateTicketRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Customers read their own tickets; the organizer of an event scans them at the entrance. The role rules are in
 * SecurityConfig: {@code POST /api/tickets/validate} is ORGANIZER, the rest of {@code /api/tickets/**} CUSTOMER.
 */
@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
class TicketController {

    private final TicketService ticketService;

    @GetMapping
    PageResponse<TicketResponse> listMine(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ticketService.listMine(CurrentUser.id(jwt), pageable);
    }

    @GetMapping("/{id}")
    TicketResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ticketService.get(CurrentUser.id(jwt), id);
    }

    /** The QR code of the ticket as a PNG image. It contains the ticket code, which the entrance scanner submits. */
    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> qr(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ticketService.qr(CurrentUser.id(jwt), id));
    }

    /** First scan: 200. Any later scan of the same ticket: 409. A ticket of another organizer's event: 404. */
    @PostMapping("/validate")
    TicketValidationResponse validate(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ValidateTicketRequest request) {
        return ticketService.validate(CurrentUser.id(jwt), request.code());
    }
}
