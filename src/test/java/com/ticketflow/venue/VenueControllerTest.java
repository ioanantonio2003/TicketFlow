package com.ticketflow.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.config.SecurityConfig;
import com.ticketflow.config.SecurityErrorHandlers;
import com.ticketflow.venue.dto.SectionResponse;
import com.ticketflow.venue.dto.VenueResponse;
import com.ticketflow.venue.dto.VenueSummary;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(VenueController.class)
@Import({SecurityConfig.class, SecurityErrorHandlers.class})
class VenueControllerTest {

    private static final String VALID_BODY = """
            {"name":"Arena","city":"Cluj","address":"Main street 1",
             "sections":[{"name":"A","rows":[{"label":"1","seats":20}]}]}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VenueService venueService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor as(long id, String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(id))).authorities(new SimpleGrantedAuthority(role));
    }

    @Test
    void customerIsForbiddenOnEveryVenueEndpoint() throws Exception {
        RequestPostProcessor customer = as(5, "ROLE_CUSTOMER");

        mockMvc.perform(post("/api/venues").with(customer).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/venues").with(customer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/venues/1").with(customer)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/venues/1").with(customer)).andExpect(status().isForbidden());
        verifyNoInteractions(venueService);
    }

    @Test
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/venues")).andExpect(status().isUnauthorized());
    }

    @Test
    void organizerCreatesVenueAndGetsLocation() throws Exception {
        VenueResponse created = new VenueResponse(9L, "Arena", "Cluj", "Main street 1",
                List.of(new SectionResponse(1L, "A", 0, 20)), 20);
        when(venueService.create(eq(7L), any())).thenReturn(created);

        mockMvc.perform(post("/api/venues").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/venues/9"))
                .andExpect(jsonPath("$.totalSeats").value(20))
                .andExpect(jsonPath("$.sections[0].seatCount").value(20));
    }

    @Test
    void adminMayAlsoManageVenues() throws Exception {
        when(venueService.list(eq(1L), any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/venues").with(as(1, "ROLE_ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void invalidLayoutListsEveryProblemWithItsPath() throws Exception {
        String body = """
                {"name":"","city":"Cluj","address":"x",
                 "sections":[{"name":"A","rows":[{"label":"1","seats":0}]}]}""";

        mockMvc.perform(post("/api/venues").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "sections[0].rows[0].seats")));
        verifyNoInteractions(venueService);
    }

    @Test
    void missingSectionsIsRejected() throws Exception {
        mockMvc.perform(post("/api/venues").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Arena\",\"city\":\"Cluj\",\"address\":\"x\",\"sections\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sections"));
    }

    @Test
    void ownerIdComesFromTheTokenNotFromTheBody() throws Exception {
        when(venueService.create(eq(7L), any())).thenReturn(
                new VenueResponse(9L, "Arena", "Cluj", "x", List.of(), 0));

        mockMvc.perform(post("/api/venues").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizerId":999,"name":"Arena","city":"Cluj","address":"x",
                                 "sections":[{"name":"A","rows":[{"label":"1","seats":1}]}]}"""))
                .andExpect(status().isCreated());

        verify(venueService).create(eq(7L), any());
    }

    @Test
    void unknownVenueIs404() throws Exception {
        when(venueService.get(7L, 99L)).thenThrow(new ResourceNotFoundException("Venue not found."));

        mockMvc.perform(get("/api/venues/99").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Venue not found."));
    }

    @Test
    void patchWithBlankNameIs400() throws Exception {
        mockMvc.perform(patch("/api/venues/1").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void patchReturnsUpdatedSummary() throws Exception {
        when(venueService.update(eq(7L), eq(1L), any())).thenReturn(new VenueSummary(1L, "New", "Cluj", "x"));

        mockMvc.perform(patch("/api/venues/1").with(as(7, "ROLE_ORGANIZER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"New\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/venues/1").with(as(7, "ROLE_ORGANIZER"))).andExpect(status().isNoContent());
        verify(venueService).delete(7L, 1L);
    }

    @Test
    void pageSizeIsCappedAndDefaultSortIsByName() throws Exception {
        when(venueService.list(eq(7L), any())).thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));

        mockMvc.perform(get("/api/venues").with(as(7, "ROLE_ORGANIZER")).param("size", "500"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/venues").with(as(7, "ROLE_ORGANIZER")))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageables = ArgumentCaptor.forClass(Pageable.class);
        verify(venueService, times(2)).list(eq(7L), pageables.capture());
        assertThat(pageables.getAllValues().get(0).getPageSize()).isEqualTo(100);
        assertThat(pageables.getAllValues().get(1).getPageSize()).isEqualTo(20);
        assertThat(pageables.getAllValues().get(1).getSort()).isEqualTo(Sort.by("name"));
    }
}
