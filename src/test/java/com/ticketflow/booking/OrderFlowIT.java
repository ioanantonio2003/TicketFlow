package com.ticketflow.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.TestcontainersConfig;
import com.ticketflow.booking.BookingTestData.PublishedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
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
import org.springframework.test.web.servlet.ResultActions;

/** Booking over HTTP with real security. Events come from SQL fixtures; customers register through the API. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class OrderFlowIT {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private BookingTestData data() {
        return new BookingTestData(jdbc);
    }

    @Test
    void customerHoldsSeatsAndSeesThemInTheOrderAndInTheSeatMap() throws Exception {
        PublishedEvent event = data().publishedEvent(10, "50.00");
        String token = customerToken();
        Instant before = Instant.now();

        String body = hold(token, event, 0, 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(100.00))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].section").value("Floor"))
                .andExpect(jsonPath("$.items[0].row").value("1"))
                .andExpect(jsonPath("$.items[0].seatNumber").value(1))
                .andExpect(jsonPath("$.items[0].price").value(50.00))
                .andReturn().getResponse().getContentAsString();

        Instant expires = Instant.parse(JsonPath.read(body, "$.holdExpiresAt"));
        assertThat(expires).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
        // the public seat map shows the seats as HELD and the availability dropped
        mockMvc.perform(get("/api/events/" + event.eventId() + "/seats"))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("HELD"))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[1].status").value("HELD"))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[2].status").value("AVAILABLE"));
        mockMvc.perform(get("/api/events/" + event.eventId()))
                .andExpect(jsonPath("$.sections[0].availableSeats").value(8));
    }

    @Test
    void orderIsVisibleOnlyToItsOwner() throws Exception {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        String owner = customerToken();
        String stranger = customerToken();
        int orderId = JsonPath.read(hold(owner, event, 0).andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/orders/" + orderId).header("Authorization", "Bearer " + stranger))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/orders/" + orderId)).andExpect(status().isUnauthorized());
    }

    @Test
    void organizersCannotBook() throws Exception {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        String organizer = roleToken("ORGANIZER");

        hold(organizer, event, 0).andExpect(status().isForbidden());
        assertThat(data().count("select count(*) from event_seats where event_id = ? and status = 'HELD'",
                event.eventId())).isZero();
    }

    @Test
    void secondCustomerCannotTakeAHeldSeatAndTheFailureIsAtomic() throws Exception {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        String first = customerToken();
        String second = customerToken();
        hold(first, event, 0).andExpect(status().isCreated());

        // seat 1 is free, seat 0 is taken: the request must fail as a whole
        hold(second, event, 1, 0).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("One or more of the selected seats are no longer available."))
                .andExpect(content().string(not(containsString(String.valueOf(event.seatIds().get(0))))));

        assertThat(data().count("select count(*) from event_seats where id = ? and status = 'AVAILABLE'",
                event.seatIds().get(1))).isEqualTo(1); // the free seat was not kept
        mockMvc.perform(get("/api/orders").header("Authorization", "Bearer " + second))
                .andExpect(jsonPath("$.totalElements").value(0)); // and no order was left behind
    }

    @Test
    void aCustomerCanHaveAtMostSixSeatsPerEvent() throws Exception {
        PublishedEvent event = data().publishedEvent(20, "10.00");
        PublishedEvent otherEvent = data().publishedEvent(20, "10.00");
        String token = customerToken();

        hold(token, event, 0, 1, 2).andExpect(status().isCreated());
        hold(token, event, 3, 4, 5).andExpect(status().isCreated());      // exactly 6
        hold(token, event, 6).andExpect(status().isUnprocessableContent())  // the 7th
                .andExpect(jsonPath("$.detail").value(containsString("at most 6")));
        hold(token, otherEvent, 0, 1).andExpect(status().isCreated());    // another event has its own limit
        hold(token, event, 6, 7, 8, 9, 10, 11, 12).andExpect(status().isUnprocessableContent()); // 7 at once

        assertThat(data().count("select count(*) from event_seats where event_id = ? and status = 'HELD'",
                event.eventId())).isEqualTo(6);
    }

    @Test
    void holdsThatHaveRunOutDoNotCountTowardsTheLimit() throws Exception {
        PublishedEvent event = data().publishedEvent(20, "10.00");
        String token = customerToken();
        hold(token, event, 0, 1, 2, 3, 4, 5).andExpect(status().isCreated());
        hold(token, event, 6).andExpect(status().isUnprocessableContent());

        jdbc.update("update orders set hold_expires_at = now() - interval '1 minute'");  // the 10 minutes passed

        hold(token, event, 6).andExpect(status().isCreated());
    }

    @Test
    void unknownDraftOrStartedEventsCannotBeBooked() throws Exception {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        String token = customerToken();

        postOrder(token, 999_999_999L, List.of(1L)).andExpect(status().isNotFound());

        jdbc.update("update events set status = 'DRAFT' where id = ?", event.eventId());
        hold(token, event, 0).andExpect(status().isNotFound());

        jdbc.update("update events set status = 'PUBLISHED', starts_at = now() - interval '1 hour' where id = ?",
                event.eventId());
        hold(token, event, 0).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("The event has already started."));
    }

    @Test
    void seatsOfAnotherEventCannotBeHeldThroughThisOne() throws Exception {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        PublishedEvent other = data().publishedEvent(5, "10.00");
        String token = customerToken();

        postOrder(token, event.eventId(), List.of(other.seatIds().get(0))).andExpect(status().isConflict());

        assertThat(data().count("select count(*) from event_seats where event_id = ? and status = 'HELD'",
                other.eventId())).isZero();
    }

    @Test
    void myOrdersArePagedNewestFirst() throws Exception {
        PublishedEvent event = data().publishedEvent(10, "10.00");
        String token = customerToken();
        int first = JsonPath.read(hold(token, event, 0).andReturn().getResponse().getContentAsString(), "$.id");
        int second = JsonPath.read(hold(token, event, 1).andReturn().getResponse().getContentAsString(), "$.id");
        int third = JsonPath.read(hold(token, event, 2).andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/api/orders").param("size", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].id").value(third))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.content[0].items.length()").value(1));
        mockMvc.perform(get("/api/orders").param("size", "2").param("page", "1")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.content[0].id").value(first));
    }

    @Test
    void cancellingFreesTheSeatsForSomeoneElseAndCannotBeRepeated() throws Exception {
        PublishedEvent event = data().publishedEvent(5, "10.00");
        String owner = customerToken();
        String other = customerToken();
        int orderId = JsonPath.read(hold(owner, event, 0, 1).andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());                         // not the owner
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(data().count("select count(*) from event_seats where event_id = ? and status = 'AVAILABLE'",
                event.eventId())).isEqualTo(5);
        mockMvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + owner))
                .andExpect(status().isConflict());                          // already cancelled
        hold(other, event, 0, 1).andExpect(status().isCreated());           // the seats can be booked again
    }

    @Test
    void cancellingFreesTheLimitForTheCustomer() throws Exception {
        PublishedEvent event = data().publishedEvent(20, "10.00");
        String token = customerToken();
        int orderId = JsonPath.read(hold(token, event, 0, 1, 2, 3, 4, 5).andReturn().getResponse()
                .getContentAsString(), "$.id");
        hold(token, event, 6).andExpect(status().isUnprocessableContent());

        mockMvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        hold(token, event, 6).andExpect(status().isCreated());
    }

    @Test
    void invalidBodiesAre400() throws Exception {
        String token = customerToken();

        mockMvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"eventId\":1,\"seatIds\":[]}"))
                .andExpect(status().isBadRequest());
    }

    // ---- helpers ----

    /** Holds the seats at the given positions (0-based) of the event's seat list. */
    private ResultActions hold(String token, PublishedEvent event, int... positions) throws Exception {
        List<Long> ids = Arrays.stream(positions).mapToObj(event.seatIds()::get).toList();
        return postOrder(token, event.eventId(), ids);
    }

    private ResultActions postOrder(String token, long eventId, List<Long> seatIds) throws Exception {
        return mockMvc.perform(post("/api/orders")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":%d,\"seatIds\":%s}".formatted(eventId, seatIds)));
    }

    private String customerToken() throws Exception {
        String email = "cust-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}"
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated());
        return login(email);
    }

    /** A user with another role, created in the database with a known password through registration. */
    private String roleToken(String role) throws Exception {
        String email = "role-" + UUID.randomUUID() + "@example.com";
        String id = JsonPath.read(mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}"
                                .formatted(email, PASSWORD)))
                .andReturn().getResponse().getContentAsString(), "$.id").toString();
        jdbc.update("update users set role = ? where id = ?", role, Long.valueOf(id));
        return login(email);
    }

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
