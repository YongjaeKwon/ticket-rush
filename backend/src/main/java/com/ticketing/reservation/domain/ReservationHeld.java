package com.ticketing.reservation.domain;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/** 좌석 홀드 성공 도메인 이벤트. */
public record ReservationHeld(
        long reservationId,
        long scheduleId,
        long seatId,
        String userId,
        LocalDateTime expiresAt,
        LocalDateTime occurredAt
) implements DomainEvent {

    public static ReservationHeld from(Reservation reservation, LocalDateTime occurredAt) {
        Objects.requireNonNull(reservation.id(), "저장된 예매만 이벤트를 만들 수 있다");
        return new ReservationHeld(reservation.id(), reservation.scheduleId(), reservation.seatId(),
                reservation.userId(), reservation.expiresAt(), occurredAt);
    }

    @Override
    public String eventType() {
        return "ReservationHeld";
    }

    @Override
    public long aggregateId() {
        return reservationId;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of("reservationId", reservationId, "scheduleId", scheduleId,
                "seatId", seatId, "userId", userId, "expiresAt", expiresAt.toString());
    }
}
