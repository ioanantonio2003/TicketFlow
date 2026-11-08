package com.ticketflow.event;

import com.ticketflow.event.dto.EventResponse;
import com.ticketflow.event.dto.EventSummary;
import com.ticketflow.event.dto.PriceResponse;
import java.util.List;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
interface EventMapper {

    EventSummary toSummary(Event event);

    PriceResponse toPriceResponse(PriceCategory priceCategory);

    /** The event's own fields are mapped by name; {@code prices} is filled from the second parameter. */
    EventResponse toResponse(Event event, List<PriceCategory> prices);
}
