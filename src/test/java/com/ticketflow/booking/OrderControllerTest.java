package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.booking.dto.OrderItemResponse;
import com.ticketflow.booking.dto.OrderResponse;
import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.config.SecurityConfig;
import com.ticketflow.config.SecurityErrorHandlers;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, SecurityErrorHandlers.class})
class OrderControllerTest {

    private static final String BODY = "{\"eventId\":3,\"seatIds\":[10,11]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(long id, String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(id))).authorities(new SimpleGrantedAuthority(role));
    }

    private static OrderResponse order() {
        return new OrderResponse(55L, 3L, OrderStatus.PENDING, Instant.parse("2030-01-01T10:10:00Z"),
                new BigDecimal("75.50"), List.of(new OrderItemResponse(10L, "Floor", "1", 4, new BigDecimal("50.00"))));
    }

    @Test
    void customerHoldsSeats() throws Exception {
        when(orderService.create(eq(7L), any())).thenReturn(order());

        mockMvc.perform(post("/api/orders").with(as(7, "ROLE_CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/orders/55"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(75.50))
                .andExpect(jsonPath("$.items[0].section").value("Floor"));
    }

    @Test
    void onlyCustomersMayBook() throws Exception {
        for (String role : List.of("ROLE_ORGANIZER", "ROLE_ADMIN")) {
            mockMvc.perform(post("/api/orders").with(as(7, role)).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/orders").with(as(7, role))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(orderService);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/orders/55")).andExpect(status().isUnauthorized());
    }

    @Test
    void theCustomerComesFromTheTokenAndPriceOrStatusCannotBeSent() throws Exception {
        when(orderService.create(eq(7L), any())).thenReturn(order());

        mockMvc.perform(post("/api/orders").with(as(7, "ROLE_CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":3,\"seatIds\":[10],\"customerId\":999,\"status\":\"PAID\","
                                + "\"totalAmount\":0.01}"))
                .andExpect(status().isCreated());

        verify(orderService).create(eq(7L), any());
    }

    @Test
    void invalidRequestListsFields() throws Exception {
        mockMvc.perform(post("/api/orders").with(as(7, "ROLE_CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("eventId", "seatIds")));
        mockMvc.perform(post("/api/orders").with(as(7, "ROLE_CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":3,\"seatIds\":[10,null]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(orderService);
    }

    @Test
    void unavailableSeatsAre409WithAGenericMessage() throws Exception {
        when(orderService.create(eq(7L), any())).thenThrow(new SeatsUnavailableException());

        mockMvc.perform(post("/api/orders").with(as(7, "ROLE_CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("One or more of the selected seats are no longer available."))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContainPattern("\\b10\\b"))   // does not name the seat
                .andExpect(content().string(not(containsString("held"))));
    }

    @Test
    void limitViolationIs422() throws Exception {
        when(orderService.create(eq(7L), any())).thenThrow(new BusinessRuleException("You can have at most 6 seats"));

        mockMvc.perform(post("/api/orders").with(as(7, "ROLE_CUSTOMER")).contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void getReturnsOwnOrderAndOthersAre404() throws Exception {
        when(orderService.get(7L, 55L)).thenReturn(order());
        when(orderService.get(7L, 99L)).thenThrow(new ResourceNotFoundException("Order not found."));

        mockMvc.perform(get("/api/orders/55").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(55));
        mockMvc.perform(get("/api/orders/99").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listIsPagedNewestFirst() throws Exception {
        when(orderService.listMine(eq(7L), any())).thenReturn(new PageResponse<>(List.of(order()), 0, 20, 1, 1));

        mockMvc.perform(get("/api/orders").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(55))
                .andExpect(jsonPath("$.totalElements").value(1));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderService).listMine(eq(7L), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    @Test
    void customerCancelsAnOrder() throws Exception {
        when(orderService.cancel(7L, 55L)).thenReturn(order());

        mockMvc.perform(post("/api/orders/55/cancel").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(55));
    }

    @Test
    void cancellingAnOrderThatIsNotPendingIs409() throws Exception {
        when(orderService.cancel(7L, 55L)).thenThrow(new ConflictException("Only a pending order can be cancelled."));

        mockMvc.perform(post("/api/orders/55/cancel").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Only a pending order can be cancelled."));
    }

    @Test
    void cancellingSomeoneElsesOrderIs404() throws Exception {
        when(orderService.cancel(7L, 99L)).thenThrow(new ResourceNotFoundException("Order not found."));

        mockMvc.perform(post("/api/orders/99/cancel").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelNeedsTheCustomerRole() throws Exception {
        mockMvc.perform(post("/api/orders/55/cancel")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/orders/55/cancel").with(as(7, "ROLE_ORGANIZER"))).andExpect(status().isForbidden());
        verifyNoInteractions(orderService);
    }
}
