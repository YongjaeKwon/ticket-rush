package com.ticketing.reservation.domain;

import java.time.LocalDateTime;
import java.util.Map;

/** 결제 승인으로 예매가 확정됐다. */
public record ReservationConfirmed(
        long reservationId,
        long scheduleId,
        long seatId,
        String userId,
        LocalDateTime occurredAt
) implements DomainEvent {

    public static ReservationConfirmed from(Reservation reservation, LocalDateTime occurredAt) {
        return new ReservationConfirmed(reservation.id(), reservation.scheduleId(),
                reservation.seatId(), reservation.userId(), occurredAt);
    }

    @Override
    public String eventType() {
        return "ReservationConfirmed";
    }

    @Override
    public long aggregateId() {
        return reservationId;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of("reservationId", reservationId, "scheduleId", scheduleId,
                "seatId", seatId, "userId", userId);
    }
}
