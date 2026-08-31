package com.ticketing.queue.adapter.in.web;

import com.ticketing.queue.application.port.in.EnterQueueUseCase;
import com.ticketing.queue.application.port.in.GetQueueStatusUseCase;
import com.ticketing.queue.application.port.in.GetQueueStatusUseCase.QueueStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 대기열 API. 1단계 클라이언트는 /me를 폴링한다(2초 간격 권장, 2단계에 SSE). */
@RestController
@RequestMapping("/api/schedules/{scheduleId}/queue")
class QueueController {

    private final EnterQueueUseCase enterQueue;
    private final GetQueueStatusUseCase getStatus;

    QueueController(EnterQueueUseCase enterQueue, GetQueueStatusUseCase getStatus) {
        this.enterQueue = enterQueue;
        this.getStatus = getStatus;
    }

    record EnterResponse(long position) {
    }

    record StatusResponse(long position, boolean admitted, String token) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    EnterResponse enter(@PathVariable long scheduleId, @RequestHeader("X-User-Id") String userId) {
        return new EnterResponse(enterQueue.enter(scheduleId, userId));
    }

    @GetMapping("/me")
    StatusResponse me(@PathVariable long scheduleId, @RequestHeader("X-User-Id") String userId) {
        QueueStatus status = getStatus.status(scheduleId, userId);
        return new StatusResponse(status.position(), status.admitted(), status.token());
    }
}
