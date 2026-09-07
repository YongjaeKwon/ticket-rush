package com.ticketing.catalog.application.service;

import com.ticketing.catalog.application.port.in.GetEventDetailUseCase;
import com.ticketing.catalog.application.port.in.GetEventsUseCase;
import com.ticketing.catalog.application.port.in.GetScheduleDetailUseCase;
import com.ticketing.catalog.application.port.in.GetSeatLayoutUseCase;
import com.ticketing.catalog.application.port.in.GetSeatStatusUseCase;
import com.ticketing.catalog.application.port.out.CatalogReader;
import com.ticketing.catalog.application.port.out.ConfirmedSeatReader;
import com.ticketing.catalog.application.port.out.SeatHoldReader;
import com.ticketing.catalog.domain.EventDetail;
import com.ticketing.catalog.domain.EventSummary;
import com.ticketing.catalog.domain.ScheduleDetail;
import com.ticketing.catalog.domain.SeatLayout;
import com.ticketing.catalog.domain.SeatStatusBitmap;
import com.ticketing.shared.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class CatalogQueryService
        implements GetEventsUseCase, GetEventDetailUseCase, GetScheduleDetailUseCase,
        GetSeatLayoutUseCase, GetSeatStatusUseCase {

    private final CatalogReader catalogReader;
    private final ConfirmedSeatReader confirmedSeatReader;
    private final SeatHoldReader seatHoldReader;

    public CatalogQueryService(CatalogReader catalogReader, ConfirmedSeatReader confirmedSeatReader,
                               SeatHoldReader seatHoldReader) {
        this.catalogReader = catalogReader;
        this.confirmedSeatReader = confirmedSeatReader;
        this.seatHoldReader = seatHoldReader;
    }

    @Override
    public List<EventSummary> getEvents() {
        return catalogReader.findEvents();
    }

    @Override
    public EventDetail getEvent(long eventId) {
        return catalogReader.findEvent(eventId)
                .orElseThrow(() -> ApiException.notFound("EVENT_NOT_FOUND", "공연이 없습니다: " + eventId));
    }

    @Override
    public ScheduleDetail getSchedule(long scheduleId) {
        return catalogReader.findSchedule(scheduleId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND", "회차가 없습니다: " + scheduleId));
    }

    @Override
    public SeatLayout getLayout(long scheduleId) {
        return catalogReader.findLayout(scheduleId)
                .orElseThrow(() -> ApiException.notFound("SCHEDULE_NOT_FOUND", "회차가 없습니다: " + scheduleId));
    }

    @Override
    public SeatStatusBitmap getStatus(long scheduleId) {
        SeatLayout layout = getLayout(scheduleId);
        return SeatStatusBitmap.of(layout,
                confirmedSeatReader.confirmedSeatIds(scheduleId),
                seatHoldReader.heldSeatIds(scheduleId));
    }
}
