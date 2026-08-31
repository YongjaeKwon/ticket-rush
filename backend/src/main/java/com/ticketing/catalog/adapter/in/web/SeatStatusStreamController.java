package com.ticketing.catalog.adapter.in.web;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 좌석 상태 SSE (ARCHITECTURE 5절, 2단계). 접속 시 스냅샷, 이후 변경분만. */
@RestController
@RequestMapping("/api/schedules/{scheduleId}/seats")
class SeatStatusStreamController {

    private final SeatStatusBroadcaster broadcaster;

    SeatStatusStreamController(SeatStatusBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable long scheduleId) {
        return broadcaster.subscribe(scheduleId);
    }
}
