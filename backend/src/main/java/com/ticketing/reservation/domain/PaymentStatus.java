package com.ticketing.reservation.domain;

/**
 * 예매가 아는 결제 진행 상태 (3단계 비동기 확정, ADR 0009).
 * 예매 상태(HELD 등)와 따로 둔다 — 거절은 전이가 아니라서 HELD 안에서 "결제 중"과 "거절됨"을
 * 구분할 표지가 필요했다. 요청 전에는 null.
 */
public enum PaymentStatus {
    /** 결제를 요청했고 결과를 기다리는 중 — 이 동안 새 요청·취소는 막는다(이중 결제 방지) */
    REQUESTED,
    /** 승인 — 예매는 CONFIRMED */
    APPROVED,
    /** 카드 거절 등 — 예매는 HELD 그대로, 남은 시간 안에 새 시도 가능 */
    DECLINED,
    /** 시스템 오류 — 되돌리기로 예매는 EXPIRED */
    FAILED
}
