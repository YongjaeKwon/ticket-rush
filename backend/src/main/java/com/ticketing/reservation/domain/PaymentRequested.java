package com.ticketing.reservation.domain;

import com.ticketing.shared.event.DomainEvent;
import com.ticketing.shared.event.PaymentEventTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

/**
 * 확정 요청이 결제를 부른다 (ADR 0008) — payment 모듈이 이 쪽지를 받아 PG를 호출한다.
 * 금액은 서버가 정한다 — 클라이언트가 보낸 금액을 믿으면 1원 결제가 가능해진다.
 */
public record PaymentRequested(
        long reservationId,
        long scheduleId,
        String userId,
        int amount,
        LocalDateTime occurredAt
) implements DomainEvent {

    public static PaymentRequested from(Reservation reservation, int amount, LocalDateTime occurredAt) {
        Objects.requireNonNull(reservation.id(), "저장된 예매만 결제를 요청할 수 있다");
        if (amount <= 0) {
            // 0원 요청은 payment 쪽 문지기가 조용히 버린다 — 예매가 영원히 결제 중으로 남지 않게 여기서 막는다
            throw new IllegalArgumentException("결제 금액은 양수여야 한다: " + amount);
        }
        return new PaymentRequested(reservation.id(), reservation.scheduleId(), reservation.userId(),
                amount, occurredAt);
    }

    @Override
    public String eventType() {
        return PaymentEventTypes.PAYMENT_REQUESTED;
    }

    @Override
    public long aggregateId() {
        return reservationId;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of("reservationId", reservationId, "scheduleId", scheduleId,
                "userId", userId, "amount", amount);
    }
}
