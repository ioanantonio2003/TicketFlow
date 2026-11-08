package com.ticketflow.reporting;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import com.ticketflow.booking.OrderPayments;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** A few real purchases through the booking API, then the numbers the organizer sees. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ReportingIT {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrderPayments orderPayments;

    private record Account(long id, String token) {
    }

    private Account account(String role) throws Exception {
        String email = role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        String registered = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}"
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(registered, "$.id")).longValue();
        jdbc.update("update users set role = ? where id = ?", role, id);
        String login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andReturn().getResponse().getContentAsString();
        return new Account(id, JsonPath.read(login, "$.accessToken"));
    }

    private long hold(Account customer, PublishedEvent event, int... positions) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int position : positions) {
            ids.add(event.seatIds().get(position));
        }
        String body = mockMvc.perform(post("/api/orders").header("Authorization", "Bearer " + customer.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventId\":%d,\"seatIds\":%s}".formatted(event.eventId(), ids)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    @Test
    void afterAFewPurchasesTheOrganizerSeesTheExactNumbers() throws Exception {
        Account organizer = account("ORGANIZER");
        PublishedEvent event = new BookingTestData(jdbc).publishedEvent(10, "20.00");
        jdbc.update("update events set organizer_id = ? where id = ?", organizer.id(), event.eventId());
        Account ana = account("CUSTOMER");
        Account bob = account("CUSTOMER");
        Account cleo = account("CUSTOMER");

        orderPayments.settle(hold(ana, event, 0, 1, 2));    // paid: 3 seats, 60.00
        orderPayments.settle(hold(bob, event, 3));          // paid: 1 seat, 20.00
        hold(cleo, event, 4, 5);                            // pending: 2 seats held
        long cancelled = hold(cleo, event, 6);
        mockMvc.perform(post("/api/orders/" + cancelled + "/cancel").header("Authorization", "Bearer " + cleo.token()))
                .andExpect(status().isOk());                // cancelled: seat free again

        mockMvc.perform(get("/api/organizer/events/" + event.eventId() + "/stats")
                        .header("Authorization", "Bearer " + organizer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.total").value(10))
                .andExpect(jsonPath("$.seats.sold").value(4))
                .andExpect(jsonPath("$.seats.held").value(2))
                .andExpect(jsonPath("$.seats.available").value(4))
                .andExpect(jsonPath("$.occupancyPercent").value(40.00))
                .andExpect(jsonPath("$.revenue").value(80.00))
                .andExpect(jsonPath("$.ordersByStatus.PAID").value(2))
                .andExpect(jsonPath("$.ordersByStatus.PENDING").value(1))
                .andExpect(jsonPath("$.ordersByStatus.CANCELLED").value(1))
                .andExpect(jsonPath("$.ordersByStatus.REFUNDED").value(0))
                .andExpect(jsonPath("$.sections.length()").value(1))
                .andExpect(jsonPath("$.sections[0].name").value("Floor"))
                .andExpect(jsonPath("$.sections[0].revenue").value(80.00));

        mockMvc.perform(get("/api/organizer/stats").header("Authorization", "Bearer " + organizer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.events").value(1))
                .andExpect(jsonPath("$.totals.soldSeats").value(4))
                .andExpect(jsonPath("$.totals.paidOrders").value(2))
                .andExpect(jsonPath("$.totals.revenue").value(80.00))
                .andExpect(jsonPath("$.events[0].eventId").value(event.eventId()))
                .andExpect(jsonPath("$.events[0].occupancyPercent").value(40.00));
    }

    @Test
    void anotherOrganizerAndCustomersCannotSeeTheEventStats() throws Exception {
        Account owner = account("ORGANIZER");
        PublishedEvent event = new BookingTestData(jdbc).publishedEvent(3, "10.00");
        jdbc.update("update events set organizer_id = ? where id = ?", owner.id(), event.eventId());

        mockMvc.perform(get("/api/organizer/events/" + event.eventId() + "/stats")
                        .header("Authorization", "Bearer " + account("ORGANIZER").token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/organizer/events/" + event.eventId() + "/stats")
                        .header("Authorization", "Bearer " + account("CUSTOMER").token()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/organizer/events/999999999/stats")
                        .header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvalidRangeIs400() throws Exception {
        Account organizer = account("ORGANIZER");

        mockMvc.perform(get("/api/organizer/stats").param("from", "2031-01-01T00:00:00Z")
                        .param("to", "2030-01-01T00:00:00Z").header("Authorization", "Bearer " + organizer.token()))
                .andExpect(status().isBadRequest());
    }
}
