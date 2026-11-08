package com.ticketflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Title and the bearer-token scheme, so that "Authorize" in Swagger UI takes the access token from
 * {@code POST /api/auth/login} and sends it with every call. Swagger UI is switched off in production.
 */
@Configuration
class OpenApiConfig {

    private static final String BEARER = "bearer-jwt";

    @Bean
    OpenAPI ticketFlowOpenApi() {
        return new OpenAPI()
                .info(new Info().title("TicketFlow API").version("1.0")
                        .description("Ticket booking for events with numbered seats. Log in with "
                                + "POST /api/auth/login, then press Authorize and paste the access token."))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
