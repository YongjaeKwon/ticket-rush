package com.ticketing.catalog.application.port.in;

import com.ticketing.catalog.domain.EventSummary;

import java.util.List;

public interface GetEventsUseCase {

    List<EventSummary> getEvents();
}
