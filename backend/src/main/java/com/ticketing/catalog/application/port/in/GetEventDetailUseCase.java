package com.ticketing.catalog.application.port.in;

import com.ticketing.catalog.domain.EventDetail;

public interface GetEventDetailUseCase {

    /** 없는 공연이면 EVENT_NOT_FOUND. */
    EventDetail getEvent(long eventId);
}
