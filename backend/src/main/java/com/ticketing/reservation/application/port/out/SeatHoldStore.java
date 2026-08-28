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

    /** 선점 해제. 키가 없어도 조용히 지나간다. */
    void release(long scheduleId, long seatId);
}
