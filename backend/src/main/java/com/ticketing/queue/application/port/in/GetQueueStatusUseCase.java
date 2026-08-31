package com.ticketing.queue.application.port.in;

/** 내 대기 상태 조회 — 클라이언트가 폴링하는 API의 심장. */
public interface GetQueueStatusUseCase {

    QueueStatus status(long scheduleId, String userId);

    /**
     * position: 1부터 시작하는 순번(입장했거나 줄에 없으면 0)
     * admitted: 입장 허가 여부 · token: 입장권 JWT(입장했을 때만)
     */
    record QueueStatus(long position, boolean admitted, String token) {
    }
}
