package com.ticketing.reservation.application.port.out;

import java.time.Duration;

/**
 * 좌석 선점 저장소 (구현: Redis SET NX EX).
 * 값으로 userId를 저장한다 — reservationId는 DB 저장 후에야 생기기 때문이고,
 * 소유자 확인 용도로는 userId면 충분하다.
 */
public interface SeatHoldStore {

    /** 선점 시도. 이미 다른 홀드가 있으면 false. */
    boolean tryHold(long scheduleId, long seatId, String userId, Duration ttl);

    /**
     * 선점 해제 — 키의 값이 이 userId일 때만 지운다(비교-삭제). 키가 없거나 남의 것이면 조용히 지나간다.
     * 왜: TTL 만료 직후 다른 사용자가 새로 선점한 키를, 뒤늦게 도착한 내 해제가 지우면 안 된다.
     */
    void release(long scheduleId, long seatId, String userId);
}
