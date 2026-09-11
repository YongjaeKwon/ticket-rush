package com.ticketing.shared.event;

/**
 * Kafka 토픽 규약 (ARCHITECTURE 4-5). 토픽은 모듈(발행자)별로 하나씩.
 * 파티션 키는 scheduleId — 같은 회차의 이벤트는 같은 파티션에 실려 순서가 보장된다.
 * (인기 회차가 한 파티션으로 쏠리는 트레이드오프는 Outbox 릴레이 ADR에서 다룬다)
 *
 * 그래서 규약이 하나 따라온다: **모든 이벤트의 payload는 scheduleId를 포함한다.**
 * 봉투에는 키 필드가 없어 릴레이가 payload에서 꺼내며, 없으면 발행 실패로 처리해야 한다.
 */
public final class Topics {

    public static final String RESERVATION_EVENTS = "reservation.events";
    public static final String PAYMENT_EVENTS = "payment.events";
    public static final String QUEUE_EVENTS = "queue.events";

    private Topics() {
    }

    /** 파티션 키 — Kafka 키는 문자열로 통일한다. */
    public static String partitionKey(long scheduleId) {
        return Long.toString(scheduleId);
    }
}
