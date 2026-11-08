package com.ticketflow.reporting;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.booking.OrderStatus;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.config.SecurityConfig;
import com.ticketflow.config.SecurityErrorHandlers;
import com.ticketflow.reporting.dto.EventStatsResponse;
import com.ticketflow.reporting.dto.OrganizerStatsResponse;
import com.ticketflow.reporting.dto.SeatCounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(ReportingController.class)
@Import({SecurityConfig.class, SecurityErrorHandlers.class})
class ReportingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportingService reportingService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(long id, String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(id))).authorities(new SimpleGrantedAuthority(role));
    }

    @Test
    void organizerReadsTheStatsOfAnEvent() throws Exception {
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        byStatus.put(OrderStatus.PAID, 2L);
        when(reportingService.eventStats(7L, 3L)).thenReturn(new EventStatsResponse(3L, "Concert", "PUBLISHED",
                new SeatCounts(10, 6, 1, 3), new BigDecimal("30.00"), new BigDecimal("50.00"), byStatus, List.of()));

        mockMvc.perform(get("/api/organizer/events/3/stats").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.sold").value(3))
                .andExpect(jsonPath("$.occupancyPercent").value(30.00))
                .andExpect(jsonPath("$.revenue").value(50.00))
                .andExpect(jsonPath("$.ordersByStatus.PAID").value(2));
    }

    @Test
    void someoneElsesEventIs404() throws Exception {
        when(reportingService.eventStats(7L, 99L)).thenThrow(new ResourceNotFoundException("Event not found."));

        mockMvc.perform(get("/api/organizer/events/99/stats").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void theRangeIsPassedAsInstants() throws Exception {
        Instant from = Instant.parse("2030-01-01T00:00:00Z");
        Instant to = Instant.parse("2030-02-01T00:00:00Z");
        when(reportingService.organizerStats(eq(7L), eq(from), eq(to))).thenReturn(new OrganizerStatsResponse(from, to,
                new OrganizerStatsResponse.Totals(0, 0, 0, BigDecimal.ZERO), List.of()));

        mockMvc.perform(get("/api/organizer/stats").param("from", "2030-01-01T00:00:00Z")
                        .param("to", "2030-02-01T00:00:00Z").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.events").value(0));
    }

    @Test
    void aBadDateIs400() throws Exception {
        mockMvc.perform(get("/api/organizer/stats").param("from", "yesterday").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reportingService);
    }

    @Test
    void customersAndAnonymousCannotSeeReports() throws Exception {
        mockMvc.perform(get("/api/organizer/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/organizer/stats").with(as(5, "ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/organizer/events/3/stats").with(as(5, "ROLE_CUSTOMER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reportingService);
    }
}
