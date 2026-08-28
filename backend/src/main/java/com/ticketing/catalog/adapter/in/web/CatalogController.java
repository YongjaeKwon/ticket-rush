package com.ticketing.catalog.adapter.in.web;

import com.ticketing.catalog.application.port.in.GetEventDetailUseCase;
import com.ticketing.catalog.application.port.in.GetEventsUseCase;
import com.ticketing.catalog.application.port.in.GetSeatLayoutUseCase;
import com.ticketing.catalog.application.port.in.GetSeatStatusUseCase;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api")
class CatalogController {

    private final GetEventsUseCase getEvents;
    private final GetEventDetailUseCase getEventDetail;
    private final GetSeatLayoutUseCase getSeatLayout;
    private final GetSeatStatusUseCase getSeatStatus;

    CatalogController(GetEventsUseCase getEvents, GetEventDetailUseCase getEventDetail,
                      GetSeatLayoutUseCase getSeatLayout, GetSeatStatusUseCase getSeatStatus) {
        this.getEvents = getEvents;
        this.getEventDetail = getEventDetail;
        this.getSeatLayout = getSeatLayout;
        this.getSeatStatus = getSeatStatus;
    }

    @GetMapping("/events")
    List<CatalogResponses.EventSummaryResponse> events() {
        return getEvents.getEvents().stream().map(CatalogResponses.EventSummaryResponse::from).toList();
    }

    @GetMapping("/events/{eventId}")
    CatalogResponses.EventDetailResponse event(@PathVariable long eventId) {
        return CatalogResponses.EventDetailResponse.from(getEventDetail.getEvent(eventId));
    }

    /** 배치는 정적 — 캐시 불변 선언, ETag는 ShallowEtagHeaderFilter가 붙인다. */
    @GetMapping("/schedules/{scheduleId}/seats/layout")
    ResponseEntity<CatalogResponses.SeatLayoutResponse> layout(@PathVariable long scheduleId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(CatalogResponses.SeatLayoutResponse.from(getSeatLayout.getLayout(scheduleId)));
    }

    @GetMapping("/schedules/{scheduleId}/seats/status")
    ResponseEntity<CatalogResponses.SeatStatusResponse> status(@PathVariable long scheduleId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(CatalogResponses.SeatStatusResponse.from(getSeatStatus.getStatus(scheduleId)));
    }
}
