package com.ticketflow.common.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest
@ActiveProfiles("error-test")
@Import({ErrorTestController.class, GlobalExceptionHandlerTest.OpenSecurity.class})
class GlobalExceptionHandlerTest {

    /** Security rules are not under test here: let everything through so only error mapping is checked. */
    @TestConfiguration
    static class OpenSecurity {
        @Bean
        SecurityFilterChain open(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void notFoundMapsTo404() throws Exception {
        assertProblem(get("/test-errors/not-found"), 404, "Resource not found", "Event 42 not found");
    }

    @Test
    void conflictMapsTo409() throws Exception {
        assertProblem(get("/test-errors/conflict"), 409, "Conflict", "Email already registered");
    }

    @Test
    void businessRuleMapsTo422() throws Exception {
        assertProblem(get("/test-errors/business-rule"), 422, "Business rule violated",
                "Event is already cancelled");
    }

    @Test
    void forbiddenOperationMapsTo403() throws Exception {
        assertProblem(get("/test-errors/forbidden"), 403, "Forbidden", "Not your order");
    }

    @Test
    void dataIntegrityViolationMapsTo409WithGenericMessage() throws Exception {
        assertProblem(get("/test-errors/data-integrity"), 409, "Conflict",
                "The request conflicts with existing data.")
                .andExpect(content().string(not(containsString("uk_secret_table"))));
    }

    @Test
    void optimisticLockMapsTo409() throws Exception {
        assertProblem(get("/test-errors/optimistic-lock"), 409, "Conflict",
                "The resource was modified by someone else. Reload it and try again.");
    }

    @Test
    void unknownPropertyMapsTo400() throws Exception {
        assertProblem(get("/test-errors/unknown-property"), 400, "Invalid request",
                "Unknown sort or filter property.");
    }

    @Test
    void unexpectedExceptionMapsTo500WithoutLeakingDetails() throws Exception {
        assertProblem(get("/test-errors/unexpected"), 500, "Internal server error", "An unexpected error occurred.")
                .andExpect(content().string(not(containsString("internal detail"))));
    }

    @Test
    void invalidBodyListsEachField() throws Exception {
        mockMvc.perform(post("/test-errors/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "quantity")));
    }

    @Test
    void invalidRequestParamListsParameterName() throws Exception {
        mockMvc.perform(get("/test-errors/param").param("count", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("count"));
    }

    @Test
    void constraintViolationExceptionListsFields() throws Exception {
        mockMvc.perform(get("/test-errors/constraint-violation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "quantity")));
    }

    @Test
    void frameworkErrorsAlsoGetRequestId() throws Exception {
        mockMvc.perform(get("/test-errors/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void responseAlwaysHasGeneratedRequestIdHeader() throws Exception {
        mockMvc.perform(get("/test-errors/not-found"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void validIncomingRequestIdIsEchoedInHeaderAndBody() throws Exception {
        mockMvc.perform(get("/test-errors/not-found").header("X-Request-Id", "abc-123"))
                .andExpect(header().string("X-Request-Id", "abc-123"))
                .andExpect(jsonPath("$.requestId").value("abc-123"));
    }

    @Test
    void unsafeIncomingRequestIdIsReplaced() throws Exception {
        mockMvc.perform(get("/test-errors/not-found").header("X-Request-Id", "bad id with spaces"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }

    private ResultActions assertProblem(org.springframework.test.web.servlet.RequestBuilder request, int status,
                                        String title, String detail) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.detail").value(detail))
                .andExpect(jsonPath("$.instance").isNotEmpty())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
}
