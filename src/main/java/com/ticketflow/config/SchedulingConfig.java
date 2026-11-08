package com.ticketflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} methods (refresh token cleanup now, hold expiry later). */
@Configuration
@EnableScheduling
class SchedulingConfig {
}
