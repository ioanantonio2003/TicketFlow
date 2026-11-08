package com.ticketflow.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.common.exception.BusinessRuleException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The state machine of CLAUDE.md section 6: DRAFT -> PUBLISHED -> COMPLETED, DRAFT|PUBLISHED -> CANCELLED. */
class EventTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant NEXT_MONTH = NOW.plus(Duration.ofDays(30));

    private Event draft() {
        return Event.draft(1L, 2L, "Concert", "desc", EventCategory.CONCERT, NEXT_MONTH, NOW);
    }

    @Test
    void newEventIsADraft() {
        assertThat(draft().getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void eventCannotStartInThePastOrNow() {
        assertThatThrownBy(() -> Event.draft(1L, 2L, "x", null, EventCategory.OTHER, NOW.minusSeconds(1), NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Event.draft(1L, 2L, "x", null, EventCategory.OTHER, NOW, NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void draftCanBeEdited() {
        Event event = draft();

        event.update("New title", null, EventCategory.THEATRE, NEXT_MONTH.plusSeconds(60), NOW);

        assertThat(event.getTitle()).isEqualTo("New title");
        assertThat(event.getCategory()).isEqualTo(EventCategory.THEATRE);
        assertThat(event.getDescription()).isNull();
    }

    @Test
    void editMustKeepTheStartInTheFuture() {
        Event event = draft();

        assertThatThrownBy(() -> event.update("t", null, EventCategory.OTHER, NOW.minusSeconds(5), NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void draftCanBePublished() {
        Event event = draft();

        event.publish(NOW);

        assertThat(event.getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void publishingTwiceIsRejected() {
        Event event = draft();
        event.publish(NOW);

        assertThatThrownBy(() -> event.publish(NOW)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("PUBLISHED");
    }

    @Test
    void eventThatAlreadyStartedCannotBePublished() {
        Event event = draft();

        assertThatThrownBy(() -> event.publish(NEXT_MONTH.plusSeconds(1)))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(event.getStatus()).isEqualTo(EventStatus.DRAFT);
    }

    @Test
    void publishedEventCannotBeEdited() {
        Event event = draft();
        event.publish(NOW);

        assertThatThrownBy(() -> event.update("t", null, EventCategory.OTHER, NEXT_MONTH, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(event::assertEditable).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void draftAndPublishedCanBeCancelled() {
        Event fromDraft = draft();
        fromDraft.cancel();
        Event fromPublished = draft();
        fromPublished.publish(NOW);
        fromPublished.cancel();

        assertThat(fromDraft.getStatus()).isEqualTo(EventStatus.CANCELLED);
        assertThat(fromPublished.getStatus()).isEqualTo(EventStatus.CANCELLED);
    }

    @Test
    void cancelledEventCannotBeCancelledPublishedOrEdited() {
        Event event = draft();
        event.cancel();

        assertThatThrownBy(event::cancel).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> event.publish(NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(event::assertEditable).isInstanceOf(BusinessRuleException.class);
    }
}
