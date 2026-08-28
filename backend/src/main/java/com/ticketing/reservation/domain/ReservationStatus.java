package com.ticketing.reservation.domain;

/**
 * 예매 상태 (ARCHITECTURE 2-2).
 * HELD → CONFIRMED | EXPIRED | CANCELLED. CONFIRMED는 최종 상태.
 * 결제 "거절"(카드 한도 등)은 전이가 아니다 — HELD 유지, 5분 안에 재시도.
 */
public enum ReservationStatus {
    HELD,
    CONFIRMED,
    EXPIRED,
    CANCELLED
}
