package com.ticketing.payment.domain;

import com.ticketing.shared.event.DomainEvent;
import com.ticketing.shared.event.PaymentEventTypes;

import java.time.LocalDateTime;
import java.util.Map;

/** 결제가 승인됐다 — reservation이 이걸 받아 확정한다. 주인공(aggregateId)은 예매다. */
public record PaymentApproved(
        long reservationId,
        long scheduleId,
        String userId,
        int amount,
        String pgTxId,
        LocalDateTime occurredAt
) implements DomainEvent {

    @Override
    public String eventType() {
        return PaymentEventTypes.PAYMENT_APPROVED;
    }

    @Override
    public long aggregateId() {
        return reservationId;
    }

    @Override
    public Map<String, Object> payload() {
        return Map.of("reservationId", reservationId, "scheduleId", scheduleId,
                "userId", userId, "amount", amount, "pgTxId", pgTxId);
    }
}
