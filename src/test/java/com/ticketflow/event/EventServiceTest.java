package com.ticketflow.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ticketflow.common.exception.BusinessRuleException;
import com.ticketflow.common.exception.ResourceNotFoundException;
import com.ticketflow.event.dto.CreateEventRequest;
import com.ticketflow.event.dto.EventResponse;
import com.ticketflow.event.dto.SetPricesRequest;
import com.ticketflow.event.dto.SetPricesRequest.SectionPrice;
import com.ticketflow.venue.VenueCatalog;
import com.ticketflow.venue.dto.SectionRef;
import com.ticketflow.venue.dto.VenueRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T10:00:00Z");
    private static final Long ORGANIZER = 7L;
    private static final Long EVENT_ID = 100L;
    private static final Long VENUE_ID = 5L;

    @Mock
    private org.springframework.context.ApplicationEventPublisher events;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private PriceCategoryRepository priceCategoryRepository;
    @Mock
    private VenueCatalog venueCatalog;

    private EventService service;
    private Event event;

    @BeforeEach
    void setUp() {
        service = new EventService(eventRepository, priceCategoryRepository, new EventMapperImpl(), venueCatalog,
                Clock.fixed(NOW, ZoneOffset.UTC), events);
        event = Event.draft(ORGANIZER, VENUE_ID, "Concert", null, EventCategory.CONCERT,
                NOW.plus(Duration.ofDays(10)), NOW);
    }

    private void eventExists() {
        when(eventRepository.findByIdAndOrganizerIdForUpdate(EVENT_ID, ORGANIZER)).thenReturn(Optional.of(event));
    }

    private void venueHasSections(String... names) {
        List<SectionRef> sections = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            sections.add(new SectionRef(10L + i, names[i]));
        }
        when(venueCatalog.sections(VENUE_ID)).thenReturn(sections);
    }

    @Test
    void createInSomeoneElsesVenueIsNotFound() {
        when(venueCatalog.requireOwned(VENUE_ID, ORGANIZER)).thenThrow(new ResourceNotFoundException("Venue not found."));

        assertThatThrownBy(() -> service.create(ORGANIZER, new CreateEventRequest(
                VENUE_ID, "t", null, EventCategory.OTHER, NOW.plusSeconds(3600))))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createSavesADraftOwnedByTheCaller() {
        when(venueCatalog.requireOwned(VENUE_ID, ORGANIZER)).thenReturn(new VenueRef(VENUE_ID, "Arena", "Cluj"));

        EventResponse response = service.create(ORGANIZER, new CreateEventRequest(
                VENUE_ID, "  Concert ", "  ", EventCategory.CONCERT, NOW.plusSeconds(3600)));

        ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(saved.capture());
        assertThat(saved.getValue().getOrganizerId()).isEqualTo(ORGANIZER);
        assertThat(saved.getValue().getStatus()).isEqualTo(EventStatus.DRAFT);
        assertThat(saved.getValue().getTitle()).isEqualTo("Concert");
        assertThat(saved.getValue().getDescription()).isNull(); // blank becomes null
        assertThat(response.prices()).isEmpty();
    }

    @Test
    void createWithPastStartIsRejected() {
        when(venueCatalog.requireOwned(VENUE_ID, ORGANIZER)).thenReturn(new VenueRef(VENUE_ID, "Arena", "Cluj"));

        assertThatThrownBy(() -> service.create(ORGANIZER, new CreateEventRequest(
                VENUE_ID, "t", null, EventCategory.OTHER, NOW.minusSeconds(1))))
                .isInstanceOf(BusinessRuleException.class);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void unknownEventIsNotFound() {
        when(eventRepository.findByIdAndOrganizerIdForUpdate(EVENT_ID, ORGANIZER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.publish(ORGANIZER, EVENT_ID)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void pricesForASectionOfAnotherVenueAreRejected() {
        eventExists();
        venueHasSections("A");

        assertThatThrownBy(() -> service.setPrices(ORGANIZER, EVENT_ID, prices(price(999L, "10.00"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("does not belong");
        verify(priceCategoryRepository, never()).save(any());
    }

    @Test
    void twoPricesForTheSameSectionAreRejected() {
        eventExists();
        venueHasSections("A");

        assertThatThrownBy(() -> service.setPrices(ORGANIZER, EVENT_ID,
                prices(price(10L, "10.00"), price(10L, "20.00"))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("more than one price");
    }

    @Test
    void setPricesUpdatesExistingCreatesNewAndDeletesRemoved() {
        eventExists();
        venueHasSections("A", "B", "C"); // section ids 10, 11, 12
        PriceCategory forA = new PriceCategory(event, 10L, new BigDecimal("50.00"));
        PriceCategory forB = new PriceCategory(event, 11L, new BigDecimal("40.00"));
        when(priceCategoryRepository.findByEventIdOrderBySectionId(EVENT_ID)).thenReturn(List.of(forA, forB));

        service.setPrices(ORGANIZER, EVENT_ID, prices(price(10L, "55.00"), price(12L, "20.00")));

        assertThat(forA.getPrice()).isEqualByComparingTo("55.00"); // updated in place
        ArgumentCaptor<PriceCategory> created = ArgumentCaptor.forClass(PriceCategory.class);
        verify(priceCategoryRepository).save(created.capture());
        assertThat(created.getValue().getSectionId()).isEqualTo(12L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<PriceCategory>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(priceCategoryRepository).deleteAll(deleted.capture()); // B is not in the request any more
        assertThat(deleted.getValue()).containsExactly(forB);
    }

    @Test
    void pricesCannotBeChangedAfterPublishing() {
        event.publish(NOW);
        eventExists();

        assertThatThrownBy(() -> service.setPrices(ORGANIZER, EVENT_ID, prices(price(10L, "10.00"))))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void publishNeedsAPriceForEverySection() {
        eventExists();
        venueHasSections("Floor", "Balcony"); // ids 10 and 11
        when(priceCategoryRepository.findByEventIdOrderBySectionId(EVENT_ID))
                .thenReturn(List.of(new PriceCategory(event, 10L, new BigDecimal("10.00"))));

        assertThatThrownBy(() -> service.publish(ORGANIZER, EVENT_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Balcony")
                .hasMessageNotContaining("Floor");
        verify(eventRepository, never()).generateEventSeats(any(), any());
    }

    @Test
    void publishGeneratesEventSeatsWithOneBulkInsert() {
        eventExists();
        venueHasSections("Floor");
        when(priceCategoryRepository.findByEventIdOrderBySectionId(EVENT_ID))
                .thenReturn(List.of(new PriceCategory(event, 10L, new BigDecimal("10.00"))));
        when(eventRepository.generateEventSeats(EVENT_ID, VENUE_ID)).thenReturn(600);
        when(venueCatalog.seatCount(VENUE_ID)).thenReturn(600L);

        EventResponse response = service.publish(ORGANIZER, EVENT_ID);

        assertThat(response.status()).isEqualTo(EventStatus.PUBLISHED);
        assertThat(response.prices()).hasSize(1);
        verify(eventRepository).generateEventSeats(EVENT_ID, VENUE_ID);
    }

    @Test
    void publishFailsIfNotEveryVenueSeatGotAnEventSeat() {
        eventExists();
        venueHasSections("Floor");
        when(priceCategoryRepository.findByEventIdOrderBySectionId(EVENT_ID))
                .thenReturn(List.of(new PriceCategory(event, 10L, new BigDecimal("10.00"))));
        when(eventRepository.generateEventSeats(EVENT_ID, VENUE_ID)).thenReturn(599);
        when(venueCatalog.seatCount(VENUE_ID)).thenReturn(600L);

        assertThatThrownBy(() -> service.publish(ORGANIZER, EVENT_ID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void publishingAnAlreadyPublishedEventIsRejectedBeforeAnyInsert() {
        event.publish(NOW);
        eventExists();

        assertThatThrownBy(() -> service.publish(ORGANIZER, EVENT_ID)).isInstanceOf(BusinessRuleException.class);
        verify(eventRepository, never()).generateEventSeats(any(), any());
    }

    private static SetPricesRequest prices(SectionPrice... prices) {
        return new SetPricesRequest(List.of(prices));
    }

    private static SectionPrice price(Long sectionId, String amount) {
        return new SectionPrice(sectionId, new BigDecimal(amount));
    }
}
