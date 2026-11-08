package com.ticketflow.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Full stack: HTTP, security, JPA, Flyway and a real PostgreSQL. Each test uses its own unique emails. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class AuthFlowIT {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registerLoginAndCallMe() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));

        String token = loginToken(email, PASSWORD);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void passwordIsStoredAsBcryptHash() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());

        String stored = jdbcTemplate.queryForObject(
                "select password_hash from users where email = ?", String.class, email);

        assertThat(stored).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    void tamperedTokenIs401ProblemDetail() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        String token = loginToken(email, PASSWORD);
        int middleOfSignature = token.lastIndexOf('.') + 10;
        char original = token.charAt(middleOfSignature);
        String tampered = token.substring(0, middleOfSignature) + (original == 'A' ? 'B' : 'A')
                + token.substring(middleOfSignature + 1);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void duplicateEmailIgnoringCaseIs409() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());

        register(email.toUpperCase()).andExpect(status().isConflict());
    }

    @Test
    void wrongPasswordAndUnknownEmailGiveTheSameAnswer() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());

        String wrongPassword = login(email, "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login(uniqueEmail(), "not-the-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(wrongPassword, "$.detail"))
                .isEqualTo(JsonPath.<String>read(unknownEmail, "$.detail"));
    }

    @Test
    void adminGrantsOrganizerAndNewTokenCarriesTheRole() throws Exception {
        String email = uniqueEmail();
        String userId = JsonPath.read(register(email).andReturn().getResponse().getContentAsString(), "$.id")
                .toString();
        String adminToken = loginToken("admin@test.local", "admin-test-password"); // created by AdminBootstrap

        mockMvc.perform(patch("/api/admin/users/" + userId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORGANIZER"));

        String newToken = loginToken(email, PASSWORD);
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + newToken))
                .andExpect(jsonPath("$.role").value("ORGANIZER"));
        // auditing recorded who made the change
        Long updatedBy = jdbcTemplate.queryForObject("select updated_by from users where id = ?", Long.class,
                Long.valueOf(userId));
        Long adminId = jdbcTemplate.queryForObject("select id from users where email = 'admin@test.local'", Long.class);
        assertThat(updatedBy).isEqualTo(adminId);
    }

    @Test
    void customerCannotUseAdminEndpointAndAdminCannotBeGrantedByApi() throws Exception {
        String email = uniqueEmail();
        String userId = JsonPath.read(register(email).andReturn().getResponse().getContentAsString(), "$.id")
                .toString();
        String customerToken = loginToken(email, PASSWORD);
        String adminToken = loginToken("admin@test.local", "admin-test-password");

        mockMvc.perform(patch("/api/admin/users/" + userId + "/role")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/admin/users/" + userId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void registrationFillsAuditColumnsWithoutAnAuthor() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());

        var row = jdbcTemplate.queryForMap("select created_at, updated_at, created_by from users where email = ?", email);

        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
        assertThat(row.get("created_by")).isNull(); // anonymous registration
    }

    @Test
    void databaseItselfRejectsEmailsThatDifferOnlyByCase() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into users (email, password_hash, display_name, role, created_at, updated_at) "
                        + "values (?, 'x', 'x', 'CUSTOMER', now(), now())", email.toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void loginReturnsBothTokensAndOnlyTheHashIsStored() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());

        String refreshToken = loginResponse(email, "$.refreshToken");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?", Integer.class, refreshToken)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where token_hash = ?", Integer.class,
                RefreshTokenService.hash(refreshToken))).isEqualTo(1);
    }

    @Test
    void refreshRotatesAndReuseOfAnOldTokenKillsTheWholeChain() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        String first = loginResponse(email, "$.refreshToken");

        String refreshBody = refresh(first).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = JsonPath.read(refreshBody, "$.refreshToken");
        String newAccessToken = JsonPath.read(refreshBody, "$.accessToken");
        String otherDevice = loginResponse(email, "$.refreshToken"); // same user, another login

        // the very first token is presented again: theft detected
        refresh(first).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        // the revocation was committed even though the request failed: no token of this user works any more
        refresh(second).andExpect(status().isUnauthorized());
        refresh(otherDevice).andExpect(status().isUnauthorized());
        // access tokens already issued stay valid until they expire (stateless)
        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk());
    }

    @Test
    void refreshIssuesAccessTokenWithTheCurrentRole() throws Exception {
        String email = uniqueEmail();
        String userId = JsonPath.read(register(email).andReturn().getResponse().getContentAsString(), "$.id")
                .toString();
        String refreshToken = loginResponse(email, "$.refreshToken");
        String adminToken = loginToken("admin@test.local", "admin-test-password");
        mockMvc.perform(patch("/api/admin/users/" + userId + "/role")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isOk());

        refresh(refreshToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ORGANIZER"));
    }

    @Test
    void expiredRefreshTokenIs401() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        String refreshToken = loginResponse(email, "$.refreshToken");
        jdbcTemplate.update("update refresh_tokens set expires_at = now() - interval '1 minute' where token_hash = ?",
                RefreshTokenService.hash(refreshToken));

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageRefreshTokenIs401() throws Exception {
        refresh("not-a-real-token").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentication failed."));
    }

    @Test
    void logoutRevokesTheRefreshTokenAndIsIdempotent() throws Exception {
        String email = uniqueEmail();
        register(email).andExpect(status().isCreated());
        String refreshToken = loginResponse(email, "$.refreshToken");

        logout(refreshToken).andExpect(status().isNoContent());
        logout(refreshToken).andExpect(status().isNoContent());
        logout("never-issued").andExpect(status().isNoContent());

        refresh(refreshToken).andExpect(status().isUnauthorized());
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private ResultActions logout(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"%s\"}".formatted(refreshToken)));
    }

    private String loginResponse(String email, String jsonPath) throws Exception {
        String body = login(email, PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, jsonPath);
    }

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Test User\"}"
                        .formatted(email, PASSWORD)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
    }

    private String loginToken(String email, String password) throws Exception {
        String body = login(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
