package com.ticketflow.booking;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HoldProperties.class)
class BookingConfig {
}
