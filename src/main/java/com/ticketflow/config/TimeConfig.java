package com.ticketflow.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class TimeConfig {

    /** Business code asks this bean for the time, never {@code Instant.now()}, so tests can fix it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
