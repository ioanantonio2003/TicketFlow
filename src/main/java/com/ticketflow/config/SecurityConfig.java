package com.ticketflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandlers errorHandlers,
                                            JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        http
                // CSRF protects browsers that send credentials automatically (cookies). This API is
                // stateless and reads the token from the Authorization header, which a malicious site
                // cannot make the browser attach, so CSRF protection would add nothing.
                .csrf(AbstractHttpConfigurer::disable)
                // uses the CorsConfigurationSource bean from CorsConfig
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health", "/actuator/health/**",
                                "/swagger-ui/**", "/v3/api-docs/**", "/error").permitAll()
                        // the provider calls the webhook without a user token: it is verified by signature instead.
                        // /fakepay/** is the simulated provider itself (an external system in real life)
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhook", "/fakepay/**").permitAll()
                        // public catalogue: browsing events needs no account
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/venues/**").hasAnyRole("ORGANIZER", "ADMIN")
                        // writes on events are for organizers
                        .requestMatchers(HttpMethod.POST, "/api/events/**").hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/events/**").hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers("/api/organizer/**").hasAnyRole("ORGANIZER", "ADMIN")
                        .requestMatchers("/api/orders/**").hasRole("CUSTOMER")
                        // scanning at the entrance is for the organizer; a customer's own tickets are theirs
                        .requestMatchers(HttpMethod.POST, "/api/tickets/validate").hasRole("ORGANIZER")
                        .requestMatchers("/api/tickets/**").hasRole("CUSTOMER")
                        .anyRequest().authenticated()) // deny by default
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        // the resource server has its own handlers for bad tokens: use the same ones
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers));
        return http.build();
    }

    /** The "roles" claim already contains values like ROLE_ADMIN, so no extra prefix is added. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
