package com.ticketing.payment.domain;

import com.ticketing.shared.event.DomainEvent;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * PG(카드사)가 결제를 거절했다 — 상태 전이가 아니다. 예매는 HELD 그대로,
 * 사용자는 남은 시간 안에 새 접수번호로 다시 시도할 수 있다 (ARCHITECTURE 2-2, ADR 0008).
 */
public record PaymentDeclined(
        long reservationId,
        long scheduleId,
        String userId,
        int amount,
        LocalDateTime occurredAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return "PaymentDeclined";
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
