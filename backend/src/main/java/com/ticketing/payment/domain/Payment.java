package com.ticketing.payment.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 결제 시도 하나의 기록. 순수 자바 — 상태는 만들어질 때 정해지고 바뀌지 않는다
 * (재시도는 새 Payment 행이다. 예매의 상태 전이와 달리 결제 시도는 불변 사실).
 */
public record Payment(
        Long id,
        long reservationId,
        int amount,
        Status status,
        String pgTxId,
        LocalDateTime createdAt
) {

    public enum Status { APPROVED, DECLINED, FAILED }

    public Payment {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 양수여야 한다: " + amount);
        }
        if (status == Status.APPROVED && (pgTxId == null || pgTxId.isBlank())) {
            throw new IllegalArgumentException("승인 기록에는 PG 승인번호가 있어야 한다");
        }
    }

    public static Payment approved(long reservationId, int amount, String pgTxId, LocalDateTime at) {
        return new Payment(null, reservationId, amount, Status.APPROVED, pgTxId, at);
    }

    public static Payment declined(long reservationId, int amount, LocalDateTime at) {
        return new Payment(null, reservationId, amount, Status.DECLINED, null, at);
    }

    public static Payment failed(long reservationId, int amount, LocalDateTime at) {
        return new Payment(null, reservationId, amount, Status.FAILED, null, at);
    }
}
