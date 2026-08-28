package com.ticketing.reservation.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/** 좌석 홀드 성공 도메인 이벤트. 이름은 과거형 — 이미 일어난 사실이다. */
public record ReservationHeld(
        long reservationId,
        long scheduleId,
        long seatId,
        String userId,
        LocalDateTime expiresAt,
        LocalDateTime occurredAt
) {
    public static ReservationHeld from(Reservation reservation, LocalDateTime occurredAt) {
        Objects.requireNonNull(reservation.id(), "저장된 예매만 이벤트를 만들 수 있다");
        return new ReservationHeld(reservation.id(), reservation.scheduleId(), reservation.seatId(),
                reservation.userId(), reservation.expiresAt(), occurredAt);
    }
}
