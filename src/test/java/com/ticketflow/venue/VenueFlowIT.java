package com.ticketflow.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.TestcontainersConfig;
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

/** Full stack with a real PostgreSQL. Every test creates its own organizers, so tests are independent. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class VenueFlowIT {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void organizerCreatesVenueWithThreeSectionsOfTenRowsOfTwentySeatsAndReadsItBack() throws Exception {
        String token = organizerToken();

        String created = createVenue(token, layout("Arena", 3, 10, 20)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalSeats").value(600))
                .andReturn().getResponse().getContentAsString();
        int venueId = JsonPath.read(created, "$.id");

        mockMvc.perform(get("/api/venues/" + venueId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Arena"))
                .andExpect(jsonPath("$.totalSeats").value(600))
                .andExpect(jsonPath("$.sections.length()").value(3))
                .andExpect(jsonPath("$.sections[0].name").value("S1"))
                .andExpect(jsonPath("$.sections[0].seatCount").value(200))
                .andExpect(jsonPath("$.sections[2].name").value("S3"))
                .andExpect(jsonPath("$.sections[2].seatCount").value(200));
        assertThat(jdbcTemplate.queryForObject("select count(*) from seats s join sections c on c.id = s.section_id "
                + "where c.venue_id = ?", Integer.class, venueId)).isEqualTo(600);
    }

    @Test
    void anotherOrganizerCannotSeeChangeOrDeleteTheVenue() throws Exception {
        String ownerToken = organizerToken();
        String otherToken = organizerToken();
        int venueId = createVenueId(ownerToken, layout("Private", 1, 1, 5));

        mockMvc.perform(get("/api/venues/" + venueId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/venues/" + venueId).header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hacked\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/venues/" + venueId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        // still intact for the owner
        mockMvc.perform(get("/api/venues/" + venueId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.name").value("Private"));
    }

    @Test
    void customerIsForbidden() throws Exception {
        String customerToken = customerToken();

        mockMvc.perform(get("/api/venues").header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isForbidden());
        createVenue(customerToken, layout("Nope", 1, 1, 1)).andExpect(status().isForbidden());
    }

    @Test
    void listShowsOnlyMyVenuesWithPaging() throws Exception {
        String token = organizerToken();
        String otherToken = organizerToken();
        createVenue(token, layout("Charlie", 1, 1, 1)).andExpect(status().isCreated());
        createVenue(token, layout("Alpha", 1, 1, 1)).andExpect(status().isCreated());
        createVenue(token, layout("Bravo", 1, 1, 1)).andExpect(status().isCreated());
        createVenue(otherToken, layout("Foreign", 1, 1, 1)).andExpect(status().isCreated());

        mockMvc.perform(get("/api/venues").param("size", "2").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Alpha"))
                .andExpect(jsonPath("$.content[1].name").value("Bravo"));
    }

    @Test
    void sortingByAnUnknownPropertyIs400() throws Exception {
        mockMvc.perform(get("/api/venues").param("sort", "doesNotExist").header("Authorization",
                        "Bearer " + organizerToken()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchChangesOnlyTheGivenFields() throws Exception {
        String token = organizerToken();
        int venueId = createVenueId(token, layout("Old name", 1, 1, 1));

        mockMvc.perform(patch("/api/venues/" + venueId).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"address\":\"  New address 5 \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Old name"))
                .andExpect(jsonPath("$.address").value("New address 5"));
    }

    @Test
    void deleteRemovesVenueSectionsAndSeats() throws Exception {
        String token = organizerToken();
        int venueId = createVenueId(token, layout("Temp", 2, 2, 3));

        mockMvc.perform(delete("/api/venues/" + venueId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/venues/" + venueId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("select count(*) from sections where venue_id = ?", Integer.class,
                venueId)).isZero();
    }

    @Test
    void layoutOverTheSeatLimitIs422AndNothingIsSaved() throws Exception {
        String token = organizerToken();
        long before = jdbcTemplate.queryForObject("select count(*) from venues", Long.class);

        // 6 sections x 1 row x 1000 seats = 6000 seats
        createVenue(token, layout("Huge", 6, 1, 1000)).andExpect(status().isUnprocessableContent());

        assertThat(jdbcTemplate.queryForObject("select count(*) from venues", Long.class)).isEqualTo(before);
    }

    @Test
    void adminCanCreateVenuesToo() throws Exception {
        String adminToken = login("admin@test.local", "admin-test-password");

        createVenue(adminToken, layout("Admin venue", 1, 1, 2)).andExpect(status().isCreated());
    }

    // ---- helpers ----

    private ResultActions createVenue(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/venues").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int createVenueId(String token, String body) throws Exception {
        String response = createVenue(token, body).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    /** JSON for a venue with sections S1..Sn, each with rows 1..rows of the given number of seats. */
    private static String layout(String name, int sections, int rows, int seatsPerRow) {
        StringBuilder json = new StringBuilder("{\"name\":\"%s\",\"city\":\"Cluj\",\"address\":\"Main street 1\","
                .formatted(name)).append("\"sections\":[");
        for (int s = 1; s <= sections; s++) {
            json.append(s > 1 ? "," : "").append("{\"name\":\"S").append(s).append("\",\"rows\":[");
            for (int r = 1; r <= rows; r++) {
                json.append(r > 1 ? "," : "").append("{\"label\":\"").append(r).append("\",\"seats\":")
                        .append(seatsPerRow).append("}");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private String customerToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        registerUser(email);
        return login(email, PASSWORD);
    }

    /** Registers a user, lets the bootstrap admin grant ORGANIZER, and logs in again to get a token with the role. */
    private String organizerToken() throws Exception {
        String email = "org-" + UUID.randomUUID() + "@example.com";
        String userId = JsonPath.read(registerUser(email), "$.id").toString();
        String adminToken = login("admin@test.local", "admin-test-password");
        mockMvc.perform(patch("/api/admin/users/" + userId + "/role").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isOk());
        return login(email, PASSWORD);
    }

    private String registerUser(String email) throws Exception {
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
