package com.ticketing.catalog.application.port.out;

import java.util.Set;

/**
 * 홀드 중인 좌석 조회. 홀드 키는 reservation이 Redis에 쓰는 데이터지만,
 * confirmed_seat와 같은 이유(ADR 0004 — 읽기 전용 데이터 의존)로 직접 읽는다.
 */
public interface SeatHoldReader {

    Set<Long> heldSeatIds(long scheduleId);
}
