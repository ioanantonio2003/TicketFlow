package com.ticketflow.venue;

import com.ticketflow.common.web.CurrentUser;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.venue.dto.CreateVenueRequest;
import com.ticketflow.venue.dto.UpdateVenueRequest;
import com.ticketflow.venue.dto.VenueResponse;
import com.ticketflow.venue.dto.VenueSummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Restricted to ORGANIZER and ADMIN by the URL rule in SecurityConfig; ownership is checked in the service. */
@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
class VenueController {

    private final VenueService venueService;

    @PostMapping
    ResponseEntity<VenueResponse> create(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreateVenueRequest request) {
        VenueResponse venue = venueService.create(CurrentUser.id(jwt), request);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(venue.id()).toUri()).body(venue);
    }

    @GetMapping
    PageResponse<VenueSummary> list(@AuthenticationPrincipal Jwt jwt,
                                    @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return venueService.list(CurrentUser.id(jwt), pageable);
    }

    @GetMapping("/{id}")
    VenueResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return venueService.get(CurrentUser.id(jwt), id);
    }

    @PatchMapping("/{id}")
    VenueSummary update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
                        @Valid @RequestBody UpdateVenueRequest request) {
        return venueService.update(CurrentUser.id(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        venueService.delete(CurrentUser.id(jwt), id);
        return ResponseEntity.noContent().build();
    }
}
