package com.ticketing.reservation.domain;

import java.time.LocalDateTime;
import java.util.Map;

/** 사용자가 예매를 취소해 좌석이 풀렸다. */
public record ReservationCancelled(
        long reservationId,
        long scheduleId,
        long seatId,
        LocalDateTime occurredAt
) implements DomainEvent {

    public static ReservationCancelled from(Reservation reservation, LocalDateTime occurredAt) {
        return new ReservationCancelled(reservation.id(), reservation.scheduleId(),
                reservation.seatId(), occurredAt);
    }

    @Override
    public String eventType() {
        return "ReservationCancelled";
    }

    @Override
    public long aggregateId() {
        return reservationId;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of("reservationId", reservationId, "scheduleId", scheduleId, "seatId", seatId);
    }
}
