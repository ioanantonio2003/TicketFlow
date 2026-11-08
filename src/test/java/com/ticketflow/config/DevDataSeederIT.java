package com.ticketflow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs the application with the "dev" profile on top of a Testcontainers database. The seeder has already
 * run at startup (it is an ApplicationRunner), so the tests check its result and that it is idempotent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles({"test", "dev"})
class DevDataSeederIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DevDataSeeder seeder;

    @Test
    void seedsUsersVenuesAndPublishedEvents() {
        assertThat(count("select count(*) from users where email in (?, ?)", DevDataSeeder.ORGANIZER_EMAIL,
                DevDataSeeder.CUSTOMER_EMAIL)).isEqualTo(2);
        assertThat(count("select count(*) from users where email = ? and role = 'ORGANIZER'",
                DevDataSeeder.ORGANIZER_EMAIL)).isEqualTo(1);
        assertThat(count("select count(*) from venues")).isEqualTo(2);
        assertThat(count("select count(*) from events where status = 'PUBLISHED'")).isEqualTo(8);
        assertThat(count("select count(distinct category) from events")).isGreaterThanOrEqualTo(4);
        assertThat(count("select count(distinct v.city) from events e join venues v on v.id = e.venue_id")).isEqualTo(2);
        // every event has all seats of its venue: 4 events x 188 seats (Cluj) + 4 events x 168 seats (Bucharest)
        assertThat(count("select count(*) from event_seats")).isEqualTo(4 * 188 + 4 * 168);
    }

    @Test
    void theExampleQueryOfThePlanWorksOnTheSeededData() throws Exception {
        mockMvc.perform(get("/api/events")
                        .param("city", "Cluj").param("category", "CONCERT").param("maxPrice", "200")
                        .param("page", "0").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].title").value("Rock Night"))
                .andExpect(jsonPath("$.content[1].title").value("Jazz Evening"))
                .andExpect(jsonPath("$.content[0].city").value("Cluj"));
    }

    @Test
    void demoOrganizerAndCustomerCanLogIn() throws Exception {
        for (String[] credentials : new String[][]{
                {DevDataSeeder.ORGANIZER_EMAIL, "organizer123"}, {DevDataSeeder.CUSTOMER_EMAIL, "customer123"}}) {
            mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(credentials[0], credentials[1])))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void runningTheSeederAgainChangesNothing() {
        int events = count("select count(*) from events");
        int users = count("select count(*) from users");

        seeder.run(new DefaultApplicationArguments());

        assertThat(count("select count(*) from events")).isEqualTo(events);
        assertThat(count("select count(*) from users")).isEqualTo(users);
        assertThat(count("select count(*) from venues")).isEqualTo(2);
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }
}
