package com.ticketing.payment.domain;

import com.ticketing.shared.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 결제가 시스템 오류·타임아웃으로 끝나지 못했다 — 되돌리기 대상.
 * reservation이 이걸 받아 홀드를 해제한다(거절과 다른 점, ADR 0008).
 */
public record PaymentFailed(
        long reservationId,
        long scheduleId,
        String userId,
        int amount,
        String reason,
        LocalDateTime occurredAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "PaymentFailed";
    }

    @Override
    public long aggregateId() {
        return reservationId;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of("reservationId", reservationId, "scheduleId", scheduleId,
                "userId", userId, "amount", amount, "reason", reason);
    }
}
