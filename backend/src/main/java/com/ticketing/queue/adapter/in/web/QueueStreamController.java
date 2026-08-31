package com.ticketing.queue.adapter.in.web;

import com.ticketing.queue.application.port.in.GetQueueStatusUseCase;
import com.ticketing.queue.application.port.in.GetQueueStatusUseCase.QueueStatus;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 대기열 SSE (ARCHITECTURE 5절, 2단계).
 * 1초마다 내 순번을 push하고, 입장되면 입장권과 함께 스트림을 닫는다.
 * 매초 데이터가 나가므로 별도 하트비트는 두지 않는다.
 *
 * userId를 쿼리 파라미터로 받는 이유: 브라우저 EventSource는 커스텀 헤더를
 * 붙일 수 없다. 인증 자체가 범위 밖 단순화(X-User-Id)라 같은 수준을 유지한다.
 */
@RestController
@RequestMapping("/api/schedules/{scheduleId}/queue")
class QueueStreamController {

    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);

    private final GetQueueStatusUseCase getStatus;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "queue-sse");
                thread.setDaemon(true);
                return thread;
            });

    QueueStreamController(GetQueueStatusUseCase getStatus) {
        this.getStatus = getStatus;
    }

    record StreamPayload(long position, boolean admitted, String token) {

        static StreamPayload from(QueueStatus status) {
            return new StreamPayload(status.position(), status.admitted(), status.token());
        }
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable long scheduleId, @RequestParam("userId") String userId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        AtomicLong sequence = new AtomicLong();

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                QueueStatus status = getStatus.status(scheduleId, userId);
                emitter.send(SseEmitter.event()
                        .name("queue-status")
                        .id(String.valueOf(sequence.incrementAndGet()))
                        .data(StreamPayload.from(status)));
                if (status.admitted()) {
                    emitter.complete();   // 입장 즉시 종료 — 클라이언트는 좌석 선택으로 이동
                }
            } catch (Exception e) {
                emitter.completeWithError(e);   // 연결이 끊기면 여기로 온다 — 태스크는 아래에서 정리
            }
        }, 0, 1, TimeUnit.SECONDS);

        emitter.onCompletion(() -> task.cancel(false));
        emitter.onTimeout(() -> task.cancel(false));
        emitter.onError(e -> task.cancel(false));
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
