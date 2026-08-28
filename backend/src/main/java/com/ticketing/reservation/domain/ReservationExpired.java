package com.ticketing.reservation.domain;

import java.time.LocalDateTime;
import java.util.Map;

/** 홀드가 만료돼 좌석이 풀렸다. */
public record ReservationExpired(
        long reservationId,
        long scheduleId,
        long seatId,
        LocalDateTime occurredAt
) implements DomainEvent {

    public static ReservationExpired from(Reservation reservation, LocalDateTime occurredAt) {
        return new ReservationExpired(reservation.id(), reservation.scheduleId(),
                reservation.seatId(), occurredAt);
    }

    @Override
    public String eventType() {
        return "ReservationExpired";
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
