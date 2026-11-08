package com.ticketflow.event;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.event.dto.EventRef;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public read-only entry point of the event feature for other features (booking). */
@Service
@RequiredArgsConstructor
public class EventCatalog {

    private final EventRepository eventRepository;

    /**
     * The event must be PUBLISHED (anything else is "not found", so drafts stay invisible) and must not
     * have started yet.
     */
    @Transactional(readOnly = true)
    public EventRef requireBookable(Long eventId, Instant now) {
        Event event = eventRepository.findById(eventId)
                .filter(e -> e.getStatus() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found."));
        if (!event.getStartsAt().isAfter(now)) {
            throw new BusinessRuleException("The event has already started.");
        }
        return new EventRef(event.getId(), event.getTitle(), event.getStartsAt());
    }
}
