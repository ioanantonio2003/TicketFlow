package com.ticketflow.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Optional filters of the public search. {@code now} is passed in so "future events" uses the Clock. */
record EventSearchCriteria(String city, EventCategory category, Instant from, Instant to, BigDecimal maxPrice,
                           String titleContains, Instant now) {
}
