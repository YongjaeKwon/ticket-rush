package com.ticketing.catalog.application.port.out;

import com.ticketing.catalog.domain.EventDetail;
import com.ticketing.catalog.domain.EventSummary;
import com.ticketing.catalog.domain.SeatLayout;

import java.util.List;
import java.util.Optional;

/** catalog 소유 테이블(event·schedule·section·seat) 조회. */
public interface CatalogReader {

    List<EventSummary> findEvents();

    Optional<EventDetail> findEvent(long eventId);

    Optional<SeatLayout> findLayout(long scheduleId);
}
