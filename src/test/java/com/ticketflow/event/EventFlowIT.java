package com.ticketflow.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.TestcontainersConfig;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

/** Full stack with a real PostgreSQL. Every test creates its own organizers and venues. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class EventFlowIT {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createSetPricesAndPublishCreatesOneEventSeatPerVenueSeat() throws Exception {
        String token = organizerToken();
        Venue venue = createVenue(token, 3, 10, 20); // 600 seats
        int eventId = createEventId(token, venue.id);

        String priced = setPrices(token, eventId, venue.sectionIds, List.of("90.00", "60.00", "30.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.prices.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(priced, "$.prices[*].sectionId")).hasSize(3);

        mockMvc.perform(post("/api/events/" + eventId + "/publish").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));

        assertThat(count("select count(*) from event_seats where event_id = ?", eventId)).isEqualTo(600);
        assertThat(count("select count(*) from event_seats where event_id = ? and status = 'AVAILABLE'", eventId))
                .isEqualTo(600);
        // 200 seats per section, each with the price of its own section
        assertThat(jdbcTemplate.queryForList("""
                select pc.section_id, pc.price, count(*) as n from event_seats es
                join price_categories pc on pc.id = es.price_category_id
                where es.event_id = ? group by 1, 2 order by 1""", eventId))
                .hasSize(3)
                .allSatisfy(row -> assertThat(row.get("n")).isEqualTo(200L));
        assertThat(jdbcTemplate.queryForObject("select status from events where id = ?", String.class, eventId))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void publishingTwiceIs422AndDoesNotDuplicateSeats() throws Exception {
        String token = organizerToken();
        Venue venue = createVenue(token, 1, 2, 5);
        int eventId = createEventId(token, venue.id);
        setPrices(token, eventId, venue.sectionIds, List.of("10.00")).andExpect(status().isOk());

        publish(token, eventId).andExpect(status().isOk());
        publish(token, eventId).andExpect(status().isUnprocessableContent());

        assertThat(count("select count(*) from event_seats where event_id = ?", eventId)).isEqualTo(10);
    }

    @Test
    void cannotPublishUntilEverySectionHasAPrice() throws Exception {
        String token = organizerToken();
        Venue venue = createVenue(token, 3, 1, 5);
        int eventId = createEventId(token, venue.id);
        setPrices(token, eventId, venue.sectionIds.subList(0, 2), List.of("10.00", "20.00")).andExpect(status().isOk());

        publish(token, eventId).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("S3")));

        assertThat(count("select count(*) from event_seats where event_id = ?", eventId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select status from events where id = ?", String.class, eventId))
                .isEqualTo("DRAFT"); // the failed publish left no trace
    }

    @Test
    void publishedEventCannotBeEditedOrRepriced() throws Exception {
        String token = organizerToken();
        Venue venue = createVenue(token, 1, 1, 3);
        int eventId = createEventId(token, venue.id);
        setPrices(token, eventId, venue.sectionIds, List.of("10.00")).andExpect(status().isOk());
        publish(token, eventId).andExpect(status().isOk());

        mockMvc.perform(put("/api/events/" + eventId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(venue.id, "Changed")))
                .andExpect(status().isUnprocessableContent());
        setPrices(token, eventId, venue.sectionIds, List.of("99.00")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void draftCanBeEditedAndPricesReplaced() throws Exception {
        String token = organizerToken();
        Venue venue = createVenue(token, 2, 1, 3);
        int eventId = createEventId(token, venue.id);
        setPrices(token, eventId, venue.sectionIds, List.of("10.00", "20.00")).andExpect(status().isOk());

        mockMvc.perform(put("/api/events/" + eventId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Renamed\",\"category\":\"THEATRE\",\"startsAt\":\"" + future() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Renamed"))
                .andExpect(jsonPath("$.category").value("THEATRE"))
                .andExpect(jsonPath("$.prices.length()").value(2));
        // second PUT: one section changes price, the other disappears
        setPrices(token, eventId, venue.sectionIds.subList(0, 1), List.of("15.50")).andExpect(status().isOk())
                .andExpect(jsonPath("$.prices.length()").value(1))
                .andExpect(jsonPath("$.prices[0].price").value(15.50));
        assertThat(count("select count(*) from price_categories where event_id = ?", eventId)).isEqualTo(1);
    }

    @Test
    void eventInSomeoneElsesVenueOrOfSomeoneElseIs404() throws Exception {
        String owner = organizerToken();
        String other = organizerToken();
        Venue ownersVenue = createVenue(owner, 1, 1, 2);
        int ownersEvent = createEventId(owner, ownersVenue.id);

        mockMvc.perform(post("/api/events").header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(ownersVenue.id, "Stolen")))
                .andExpect(status().isNotFound());
        publish(other, ownersEvent).andExpect(status().isNotFound());
        setPrices(other, ownersEvent, ownersVenue.sectionIds, List.of("10.00")).andExpect(status().isNotFound());
        mockMvc.perform(put("/api/events/" + ownersEvent).header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(ownersVenue.id, "Hacked")))
                .andExpect(status().isNotFound());
    }

    @Test
    void pricesForASectionOfAnotherVenueAreRejected() throws Exception {
        String token = organizerToken();
        Venue first = createVenue(token, 1, 1, 2);
        Venue second = createVenue(token, 1, 1, 2);
        int eventId = createEventId(token, first.id);

        setPrices(token, eventId, second.sectionIds, List.of("10.00")).andExpect(status().isUnprocessableContent());
    }

    @Test
    void eventMustStartInTheFuture() throws Exception {
        String token = organizerToken();
        Venue venue = createVenue(token, 1, 1, 2);
        String past = Instant.now().minus(1, ChronoUnit.DAYS).toString();

        mockMvc.perform(post("/api/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":%d,\"title\":\"Old\",\"category\":\"OTHER\",\"startsAt\":\"%s\"}"
                                .formatted(venue.id, past)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void customerIsForbidden() throws Exception {
        String customer = customerToken();

        mockMvc.perform(post("/api/events").header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(1, "x")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/organizer/events").header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void organizerListShowsOnlyOwnEventsPaged() throws Exception {
        String token = organizerToken();
        String other = organizerToken();
        Venue venue = createVenue(token, 1, 1, 2);
        Venue otherVenue = createVenue(other, 1, 1, 2);
        createEventId(token, venue.id);
        createEventId(token, venue.id);
        createEventId(token, venue.id);
        createEventId(other, otherVenue.id);

        mockMvc.perform(get("/api/organizer/events").param("size", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].status").value("DRAFT"));
    }

    @Test
    void venueWithEventsCannotBeDeleted() throws Exception {
        String token = organizerToken();
        Venue venue = createVenue(token, 1, 1, 2);
        createEventId(token, venue.id);

        mockMvc.perform(delete("/api/venues/" + venue.id).header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("The venue has events and cannot be deleted."));

        // the venue is untouched
        mockMvc.perform(get("/api/venues/" + venue.id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void venueWithoutEventsCanStillBeDeleted() throws Exception {
        String token = organizerToken();
        Venue venue = createVenue(token, 1, 1, 2);

        mockMvc.perform(delete("/api/venues/" + venue.id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    // ---- helpers ----

    private record Venue(int id, List<Long> sectionIds) {
    }

    private Venue createVenue(String token, int sections, int rows, int seatsPerRow) throws Exception {
        StringBuilder json = new StringBuilder("{\"name\":\"Arena\",\"city\":\"Cluj\",\"address\":\"Street 1\",\"sections\":[");
        for (int s = 1; s <= sections; s++) {
            json.append(s > 1 ? "," : "").append("{\"name\":\"S").append(s).append("\",\"rows\":[");
            for (int r = 1; r <= rows; r++) {
                json.append(r > 1 ? "," : "").append("{\"label\":\"").append(r).append("\",\"seats\":")
                        .append(seatsPerRow).append("}");
            }
            json.append("]}");
        }
        json.append("]}");
        String body = mockMvc.perform(post("/api/venues").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        List<Integer> ids = JsonPath.read(body, "$.sections[*].id");
        return new Venue(JsonPath.read(body, "$.id"), ids.stream().map(Integer::longValue).toList());
    }

    private static String future() {
        return Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static String eventJson(int venueId, String title) {
        return "{\"venueId\":%d,\"title\":\"%s\",\"description\":\"d\",\"category\":\"CONCERT\",\"startsAt\":\"%s\"}"
                .formatted(venueId, title, future());
    }

    private int createEventId(String token, int venueId) throws Exception {
        String body = mockMvc.perform(post("/api/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson(venueId, "Concert")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions setPrices(String token, int eventId, List<Long> sectionIds, List<String> prices)
            throws Exception {
        StringBuilder json = new StringBuilder("{\"prices\":[");
        for (int i = 0; i < sectionIds.size(); i++) {
            json.append(i > 0 ? "," : "").append("{\"sectionId\":").append(sectionIds.get(i))
                    .append(",\"price\":").append(prices.get(i)).append("}");
        }
        json.append("]}");
        return mockMvc.perform(put("/api/events/" + eventId + "/prices").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.toString()));
    }

    private ResultActions publish(String token, int eventId) throws Exception {
        return mockMvc.perform(post("/api/events/" + eventId + "/publish").header("Authorization", "Bearer " + token));
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private String customerToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        register(email);
        return login(email, PASSWORD);
    }

    /** Registers a user, lets the bootstrap admin grant ORGANIZER, and logs in again to get a token with the role. */
    private String organizerToken() throws Exception {
        String email = "org-" + UUID.randomUUID() + "@example.com";
        String userId = JsonPath.read(register(email), "$.id").toString();
        String adminToken = login("admin@test.local", "admin-test-password");
        mockMvc.perform(patch("/api/admin/users/" + userId + "/role").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isOk());
        return login(email, PASSWORD);
    }

    private String register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}"
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
