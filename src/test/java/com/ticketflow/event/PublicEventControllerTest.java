package com.ticketflow.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.config.SecurityConfig;
import com.ticketflow.config.SecurityErrorHandlers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The real EventSearchService runs here (so request validation is real); only its repositories are mocked.
 * Everything below is called without a token: the catalogue is public.
 */
@WebMvcTest(PublicEventController.class)
@Import({SecurityConfig.class, SecurityErrorHandlers.class, EventSearchService.class,
        PublicEventControllerTest.FixedClock.class})
class PublicEventControllerTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventRepository eventRepository;
    @MockitoBean
    private EventReadRepository readRepository;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private void emptyResult() {
        when(eventRepository.findAll(org.mockito.ArgumentMatchers.<Specification<Event>>any(), any(Pageable.class)))
                .thenReturn(Page.empty());
    }

    @Test
    void listIsPublicAndUsesDefaults() throws Exception {
        emptyResult();

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(eventRepository).findAll(org.mockito.ArgumentMatchers.<Specification<Event>>any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        emptyResult();

        mockMvc.perform(get("/api/events").param("size", "5000")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(eventRepository).findAll(org.mockito.ArgumentMatchers.<Specification<Event>>any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void allFiltersAreAccepted() throws Exception {
        emptyResult();

        mockMvc.perform(get("/api/events")
                        .param("city", "Cluj").param("category", "CONCERT")
                        .param("from", "2031-01-01T00:00:00Z").param("to", "2031-12-31T23:59:59Z")
                        .param("maxPrice", "200").param("q", "rock")
                        .param("sort", "minPrice,desc").param("page", "0").param("size", "5"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidSortPropertyIs400() throws Exception {
        mockMvc.perform(get("/api/events").param("sort", "organizerId,desc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value(containsString("Allowed: minPrice, startsAt, title")));
        verifyNoInteractions(eventRepository);
    }

    @Test
    void unknownCategoryIs400() throws Exception {
        mockMvc.perform(get("/api/events").param("category", "PARTY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(eventRepository);
    }

    @Test
    void malformedDateAndPriceAre400() throws Exception {
        mockMvc.perform(get("/api/events").param("from", "tomorrow")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/events").param("maxPrice", "cheap")).andExpect(status().isBadRequest());
    }

    @Test
    void fromAfterToIs400() throws Exception {
        mockMvc.perform(get("/api/events")
                        .param("from", "2031-02-01T00:00:00Z").param("to", "2031-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeMaxPriceIs400() throws Exception {
        mockMvc.perform(get("/api/events").param("maxPrice", "-5")).andExpect(status().isBadRequest());
    }

    @Test
    void detailsOfADraftOrMissingEventIs404() throws Exception {
        when(eventRepository.findWithVenueByIdAndStatus(7L, EventStatus.PUBLISHED)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/events/7"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Event not found."));
    }

    @Test
    void seatMapOfANonPublishedEventIs404() throws Exception {
        when(eventRepository.existsByIdAndStatus(7L, EventStatus.PUBLISHED)).thenReturn(false);

        mockMvc.perform(get("/api/events/7/seats")).andExpect(status().isNotFound());
        verifyNoInteractions(readRepository);
    }

    @Test
    void writingToEventsStillNeedsAToken() throws Exception {
        mockMvc.perform(post("/api/events/7/publish")).andExpect(status().isUnauthorized());
    }
}
