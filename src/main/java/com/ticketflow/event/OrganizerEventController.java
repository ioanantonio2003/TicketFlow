package com.ticketflow.event;

import com.ticketflow.common.web.CurrentUser;
import com.ticketflow.common.web.PageResponse;
import com.ticketflow.event.dto.EventSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer/events")
@RequiredArgsConstructor
class OrganizerEventController {

    private final EventService eventService;

    /** The organizer's own events, nearest first by default. */
    @GetMapping
    PageResponse<EventSummary> listMine(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20, sort = "startsAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return eventService.listMine(CurrentUser.id(jwt), pageable);
    }
}
