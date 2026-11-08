package com.ticketflow.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.TestcontainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** CORS preflight rules and the API documentation, with the real security chain. */
@SpringBootTest(properties = "app.cors.allowed-origins=https://app.example.com")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class CorsAndDocsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightFromAnAllowedOriginIsAnswered() throws Exception {
        mockMvc.perform(options("/api/orders").header("Origin", "https://app.example.com")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
                .andExpect(header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("POST")));
    }

    @Test
    void preflightFromAnotherOriginIsRefused() throws Exception {
        mockMvc.perform(options("/api/orders").header("Origin", "https://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void aSimpleRequestFromAnotherOriginGetsNoCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/events").header("Origin", "https://evil.example.com"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void theApiDocumentationDescribesTheBearerSchemeSoSwaggerCanAuthorize() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TicketFlow API"))
                .andExpect(jsonPath("$.components.securitySchemes['bearer-jwt'].scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/orders']").exists());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
