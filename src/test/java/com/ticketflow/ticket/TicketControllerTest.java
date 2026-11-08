package com.ticketflow.ticket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.common.exception.ConflictException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.config.SecurityConfig;
import com.ticketflow.config.SecurityErrorHandlers;
import com.ticketflow.ticket.dto.TicketResponse;
import com.ticketflow.ticket.dto.TicketValidationResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(TicketController.class)
@Import({SecurityConfig.class, SecurityErrorHandlers.class})
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(long id, String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(id))).authorities(new SimpleGrantedAuthority(role));
    }

    private static TicketResponse ticket() {
        return new TicketResponse(9L, 55L, 3L, "Concert", "Floor", "1", 4, TicketStatus.VALID, "abc", null);
    }

    @Test
    void customerListsAndReadsOwnTickets() throws Exception {
        when(ticketService.listMine(org.mockito.ArgumentMatchers.eq(7L), any()))
                .thenReturn(new PageResponse<>(List.of(ticket()), 0, 20, 1, 1));
        when(ticketService.get(7L, 9L)).thenReturn(ticket());

        mockMvc.perform(get("/api/tickets").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].code").value("abc"));
        mockMvc.perform(get("/api/tickets/9").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.seatNumber").value(4));
    }

    @Test
    void qrIsServedAsPng() throws Exception {
        when(ticketService.qr(7L, 9L)).thenReturn(new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        mockMvc.perform(get("/api/tickets/9/qr").with(as(7, "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[] {(byte) 0x89, 'P', 'N', 'G'}));
    }

    @Test
    void someoneElsesTicketIs404() throws Exception {
        when(ticketService.get(7L, 99L)).thenThrow(new ResourceNotFoundException("Ticket not found."));

        mockMvc.perform(get("/api/tickets/99").with(as(7, "ROLE_CUSTOMER"))).andExpect(status().isNotFound());
    }

    @Test
    void organizerValidatesAndASecondScanIs409() throws Exception {
        when(ticketService.validate(20L, "abc")).thenReturn(
                new TicketValidationResponse(9L, 3L, "Concert", "Floor", "1", 4, Instant.parse("2030-01-01T10:00:00Z")));

        mockMvc.perform(post("/api/tickets/validate").with(as(20, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ticketId").value(9));

        when(ticketService.validate(20L, "abc")).thenThrow(new ConflictException("This ticket has already been used."));
        mockMvc.perform(post("/api/tickets/validate").with(as(20, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This ticket has already been used."));
    }

    @Test
    void validationNeedsACode() throws Exception {
        mockMvc.perform(post("/api/tickets/validate").with(as(20, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ticketService);
    }

    @Test
    void rolesAreSeparated() throws Exception {
        mockMvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/tickets").with(as(20, "ROLE_ORGANIZER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tickets/validate").with(as(7, "ROLE_CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"abc\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tickets/validate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(ticketService);
    }
}
