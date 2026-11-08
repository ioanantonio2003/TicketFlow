package com.ticketflow.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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

/** Organizers publish through the API, anonymous visitors browse. Each test uses its own unique city. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PublicEventFlowIT {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void anonymousVisitorFindsPublishedEventsWithFiltersAndNoLogin() throws Exception {
        String token = organizerToken();
        String city = "City" + System.nanoTime();
        String otherCity = "Other" + System.nanoTime();
        int venue = createVenue(token, city, 2, 2, 5);        // sections of 10 seats
        int otherVenue = createVenue(token, otherCity, 1, 1, 4);
        publishedEvent(token, venue, "Rock Night", "CONCERT", 10, "120.00", "80.00");
        publishedEvent(token, venue, "Opera Gala", "THEATRE", 20, "300.00", "250.00");
        publishedEvent(token, otherVenue, "Rock Elsewhere", "CONCERT", 15, "50.00");

        mockMvc.perform(get("/api/events").param("city", city.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Rock Night"))     // nearest first
                .andExpect(jsonPath("$.content[0].venueName").value("Arena"))
                .andExpect(jsonPath("$.content[0].city").value(city))
                .andExpect(jsonPath("$.content[0].minPrice").value(80.00))
                .andExpect(jsonPath("$.content[0].availableSeats").value(20))
                .andExpect(jsonPath("$.content[1].title").value("Opera Gala"));

        mockMvc.perform(get("/api/events").param("city", city).param("category", "CONCERT").param("maxPrice", "100"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Rock Night"));
        mockMvc.perform(get("/api/events").param("city", city).param("q", "opera").param("sort", "minPrice,desc"))
                .andExpect(jsonPath("$.content[0].title").value("Opera Gala"));
        mockMvc.perform(get("/api/events").param("city", city).param("maxPrice", "10"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void draftPastAndCancelledEventsAreHidden() throws Exception {
        String token = organizerToken();
        String city = "City" + System.nanoTime();
        int venue = createVenue(token, city, 1, 1, 3);
        int visible = publishedEvent(token, venue, "Visible", "CONCERT", 10, "10.00");
        int draft = createEvent(token, venue, "Draft", "CONCERT", 10);
        int past = publishedEvent(token, venue, "Already over", "CONCERT", 10, "10.00");
        int cancelled = publishedEvent(token, venue, "Cancelled", "CONCERT", 10, "10.00");
        jdbcTemplate.update("update events set starts_at = now() - interval '1 day' where id = ?", past);
        jdbcTemplate.update("update events set status = 'CANCELLED' where id = ?", cancelled);

        mockMvc.perform(get("/api/events").param("city", city))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(visible));

        mockMvc.perform(get("/api/events/" + draft)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/events/" + draft + "/seats")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/events/" + cancelled)).andExpect(status().isNotFound());
    }

    @Test
    void detailsShowVenueAndPriceAndAvailabilityPerSection() throws Exception {
        String token = organizerToken();
        int venue = createVenue(token, "City" + System.nanoTime(), 2, 2, 5);
        int event = publishedEvent(token, venue, "Details", "SPORT", 10, "75.50", "40.00");
        // simulate bookings made later: 1 sold and 2 held seats in the first section
        jdbcTemplate.update("""
                update event_seats set status = 'SOLD' where id = (select min(es.id) from event_seats es
                join price_categories pc on pc.id = es.price_category_id where es.event_id = ?
                and pc.section_id = (select min(section_id) from price_categories where event_id = ?))""", event, event);
        jdbcTemplate.update("""
                update event_seats set status = 'HELD' where id in (select es.id from event_seats es
                join price_categories pc on pc.id = es.price_category_id where es.event_id = ? and es.status = 'AVAILABLE'
                and pc.section_id = (select min(section_id) from price_categories where event_id = ?) limit 2)""", event, event);

        mockMvc.perform(get("/api/events/" + event))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Details"))
                .andExpect(jsonPath("$.category").value("SPORT"))
                .andExpect(jsonPath("$.venue.name").value("Arena"))
                .andExpect(jsonPath("$.venue.address").value("Main street 1"))
                .andExpect(jsonPath("$.sections.length()").value(2))
                .andExpect(jsonPath("$.sections[0].name").value("S1"))
                .andExpect(jsonPath("$.sections[0].price").value(75.50))
                .andExpect(jsonPath("$.sections[0].totalSeats").value(10))
                .andExpect(jsonPath("$.sections[0].availableSeats").value(7))
                .andExpect(jsonPath("$.sections[1].name").value("S2"))
                .andExpect(jsonPath("$.sections[1].availableSeats").value(10));
    }

    @Test
    void seatMapGroupsSeatsBySectionAndRowInVenueOrder() throws Exception {
        String token = organizerToken();
        int venue = createVenue(token, "City" + System.nanoTime(), 2, 3, 4); // 2 sections x 3 rows x 4 seats
        int event = publishedEvent(token, venue, "Map", "OTHER", 10, "20.00", "30.00");

        String body = mockMvc.perform(get("/api/events/" + event + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(event))
                .andExpect(jsonPath("$.sections.length()").value(2))
                .andExpect(jsonPath("$.sections[0].name").value("S1"))
                .andExpect(jsonPath("$.sections[0].price").value(20.00))
                .andExpect(jsonPath("$.sections[1].price").value(30.00))
                .andExpect(jsonPath("$.sections[0].rows.length()").value(3))
                .andExpect(jsonPath("$.sections[0].rows[*].label", org.hamcrest.Matchers.contains("1", "2", "3")))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[*].number",
                        org.hamcrest.Matchers.contains(1, 2, 3, 4)))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].status").value("AVAILABLE"))
                .andReturn().getResponse().getContentAsString();

        List<Integer> ids = JsonPath.read(body, "$.sections[*].rows[*].seats[*].id");
        assertThat(ids).hasSize(24).doesNotHaveDuplicates(); // every seat once, as an event-seat id
        // the ids are real event-seat ids of this event
        List<Long> stored = jdbcTemplate.queryForList("select id from event_seats where event_id = ?", Long.class, event);
        assertThat(ids.stream().map(Integer::longValue).toList()).containsExactlyInAnyOrderElementsOf(stored);
    }

    @Test
    void listFollowsPublicationWithoutAnyToken() throws Exception {
        String token = organizerToken();
        String city = "City" + System.nanoTime();
        int venue = createVenue(token, city, 1, 1, 2);
        int event = createEvent(token, venue, "Soon public", "CONCERT", 10);

        mockMvc.perform(get("/api/events").param("city", city)).andExpect(jsonPath("$.totalElements").value(0));

        setPrice(token, event, venue, "10.00");
        mockMvc.perform(post("/api/events/" + event + "/publish").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/events").param("city", city))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[*].title", hasItem("Soon public")))
                .andExpect(jsonPath("$.content[*].title", not(hasItem("Draft"))));
    }

    // ---- helpers ----

    private int publishedEvent(String token, int venueId, String title, String category, int daysAhead,
                               String... prices) throws Exception {
        int event = createEvent(token, venueId, title, category, daysAhead);
        List<Integer> sections = JsonPath.read(mockMvc.perform(get("/api/venues/" + venueId)
                        .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString(),
                "$.sections[*].id");
        StringBuilder json = new StringBuilder("{\"prices\":[");
        for (int i = 0; i < sections.size(); i++) {
            json.append(i > 0 ? "," : "").append("{\"sectionId\":").append(sections.get(i))
                    .append(",\"price\":").append(prices[Math.min(i, prices.length - 1)]).append("}");
        }
        json.append("]}");
        mockMvc.perform(put("/api/events/" + event + "/prices").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.toString())).andExpect(status().isOk());
        mockMvc.perform(post("/api/events/" + event + "/publish").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        return event;
    }

    private void setPrice(String token, int eventId, int venueId, String price) throws Exception {
        Integer section = JsonPath.read(mockMvc.perform(get("/api/venues/" + venueId)
                        .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString(),
                "$.sections[0].id");
        mockMvc.perform(put("/api/events/" + eventId + "/prices").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"prices\":[{\"sectionId\":%d,\"price\":%s}]}".formatted(section, price)))
                .andExpect(status().isOk());
    }

    private int createEvent(String token, int venueId, String title, String category, int daysAhead)
            throws Exception {
        String startsAt = Instant.now().plus(daysAhead, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        String body = mockMvc.perform(post("/api/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":%d,\"title\":\"%s\",\"category\":\"%s\",\"startsAt\":\"%s\"}"
                                .formatted(venueId, title, category, startsAt)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** Sections S1..Sn, each with `rows` rows of `seats` seats. */
    private int createVenue(String token, String city, int sections, int rows, int seats) throws Exception {
        StringBuilder json = new StringBuilder(
                "{\"name\":\"Arena\",\"city\":\"%s\",\"address\":\"Main street 1\",\"sections\":[".formatted(city));
        for (int s = 1; s <= sections; s++) {
            json.append(s > 1 ? "," : "").append("{\"name\":\"S").append(s).append("\",\"rows\":[");
            for (int r = 1; r <= rows; r++) {
                json.append(r > 1 ? "," : "").append("{\"label\":\"").append(r).append("\",\"seats\":")
                        .append(seats).append("}");
            }
            json.append("]}");
        }
        json.append("]}");
        String body = mockMvc.perform(post("/api/venues").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String organizerToken() throws Exception {
        String email = "org-" + UUID.randomUUID() + "@example.com";
        String registered = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test\"}"
                                .formatted(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String adminToken = login("admin@test.local", "admin-test-password");
        mockMvc.perform(patch("/api/admin/users/" + JsonPath.read(registered, "$.id") + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isOk());
        return login(email, PASSWORD);
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
