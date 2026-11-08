package com.ticketflow.event;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.config.SecurityConfig;
import com.ticketflow.config.SecurityErrorHandlers;
import com.ticketflow.event.dto.EventResponse;
import com.ticketflow.event.dto.EventSummary;
import com.ticketflow.event.dto.PriceResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest({EventController.class, OrganizerEventController.class})
@Import({SecurityConfig.class, SecurityErrorHandlers.class})
class EventControllerTest {

    private static final String CREATE_BODY = """
            {"venueId":5,"title":"Concert","description":"Nice","category":"CONCERT","startsAt":"2031-05-01T18:00:00Z"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(long id, String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(id))).authorities(new SimpleGrantedAuthority(role));
    }

    private static EventResponse response(EventStatus status) {
        return new EventResponse(3L, 5L, "Concert", "Nice", EventCategory.CONCERT,
                Instant.parse("2031-05-01T18:00:00Z"), status, List.of(new PriceResponse(10L, new BigDecimal("50.00"))));
    }

    @Test
    void customerIsForbiddenOnOrganizerOperations() throws Exception {
        RequestPostProcessor customer = as(5, "ROLE_CUSTOMER");

        mockMvc.perform(post("/api/events").with(customer).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/events/3/prices").with(customer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\":[{\"sectionId\":1,\"price\":10}]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/events/3/publish").with(customer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/organizer/events").with(customer)).andExpect(status().isForbidden());
        verifyNoInteractions(eventService);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/events/3/publish")).andExpect(status().isUnauthorized());
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(eventService.create(eq(7L), any())).thenReturn(response(EventStatus.DRAFT));

        mockMvc.perform(post("/api/events").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/events/3"))
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void clientCannotSetStatusOrOwner() throws Exception {
        when(eventService.create(eq(7L), any())).thenReturn(response(EventStatus.DRAFT));

        mockMvc.perform(post("/api/events").with(as(7, "ROLE_ORGANIZER")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"venueId":5,"title":"Concert","category":"CONCERT","startsAt":"2031-05-01T18:00:00Z",
                                 "status":"PUBLISHED","organizerId":999}"""))
                .andExpect(status().isCreated());

        verify(eventService).create(eq(7L), any()); // owner from the token; status is not even a request field
    }

    @Test
    void createValidationListsFields() throws Exception {
        mockMvc.perform(post("/api/events").with(as(7, "ROLE_ORGANIZER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\",\"category\":\"CONCERT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("venueId", "title", "startsAt")));
        verifyNoInteractions(eventService);
    }

    @Test
    void unknownCategoryIs400() throws Exception {
        mockMvc.perform(post("/api/events").with(as(7, "ROLE_ORGANIZER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"venueId\":5,\"title\":\"t\",\"category\":\"PARTY\",\"startsAt\":\"2031-05-01T18:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pricesValidation() throws Exception {
        mockMvc.perform(put("/api/events/3/prices").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\":[{\"price\":0},{\"sectionId\":1,\"price\":10.123}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder(
                        "prices[0].sectionId", "prices[0].price", "prices[1].price")));
        verifyNoInteractions(eventService);
    }

    @Test
    void emptyPriceListIs400() throws Exception {
        mockMvc.perform(put("/api/events/3/prices").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"prices\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void setPricesReturnsTheEvent() throws Exception {
        when(eventService.setPrices(eq(7L), eq(3L), any())).thenReturn(response(EventStatus.DRAFT));

        mockMvc.perform(put("/api/events/3/prices").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prices\":[{\"sectionId\":10,\"price\":50.00}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices[0].price").value(50.00));
    }

    @Test
    void publishingTwiceIs422() throws Exception {
        when(eventService.publish(7L, 3L)).thenThrow(new BusinessRuleException("Only a DRAFT event can be published."));

        mockMvc.perform(post("/api/events/3/publish").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Only a DRAFT event can be published."));
    }

    @Test
    void publishReturnsPublishedEvent() throws Exception {
        when(eventService.publish(7L, 3L)).thenReturn(response(EventStatus.PUBLISHED));

        mockMvc.perform(post("/api/events/3/publish").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void updateIsAFullReplacement() throws Exception {
        mockMvc.perform(put("/api/events/3").with(as(7, "ROLE_ORGANIZER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("category", "startsAt")));
    }

    @Test
    void organizerListsOwnEvents() throws Exception {
        when(eventService.listMine(eq(7L), any())).thenReturn(new PageResponse<>(
                List.of(new EventSummary(3L, 5L, "Concert", EventCategory.CONCERT,
                        Instant.parse("2031-05-01T18:00:00Z"), EventStatus.DRAFT)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/organizer/events").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Concert"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void organizerCancelsAnEvent() throws Exception {
        when(eventService.cancel(7L, 3L)).thenReturn(response(EventStatus.CANCELLED));

        mockMvc.perform(post("/api/events/3/cancel").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancellingIsForOrganizersAndSomeoneElsesEventIs404() throws Exception {
        mockMvc.perform(post("/api/events/3/cancel").with(as(5, "ROLE_CUSTOMER"))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/events/3/cancel")).andExpect(status().isUnauthorized());
        verifyNoInteractions(eventService);

        when(eventService.cancel(7L, 99L)).thenThrow(new com.ticketflow.common.exception.ResourceNotFoundException("Event not found."));
        mockMvc.perform(post("/api/events/99/cancel").with(as(7, "ROLE_ORGANIZER"))).andExpect(status().isNotFound());
    }
}
