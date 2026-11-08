package com.ticketflow.booking;

import com.ticketflow.booking.dto.CreateOrderRequest;
import com.ticketflow.booking.dto.OrderResponse;
import com.ticketflow.common.web.CurrentUser;
import com.ticketflow.common.web.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Customer operations; /api/orders/** is restricted to the CUSTOMER role in SecurityConfig. */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
class OrderController {

    private final OrderService orderService;

    /** Holds the seats for the hold duration (default 10 minutes) and returns the PENDING order. */
    @PostMapping
    ResponseEntity<OrderResponse> create(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreateOrderRequest request) {
        OrderResponse order = orderService.create(CurrentUser.id(jwt), request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(order.id()).toUri()).body(order);
    }

    /** Gives up a PENDING order: it becomes CANCELLED and its seats are released. */
    @PostMapping("/{id}/cancel")
    OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.cancel(CurrentUser.id(jwt), id);
    }

    @GetMapping("/{id}")
    OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return orderService.get(CurrentUser.id(jwt), id);
    }

    /** My orders, newest first. */
    @GetMapping
    PageResponse<OrderResponse> listMine(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return orderService.listMine(CurrentUser.id(jwt), pageable);
    }
}
