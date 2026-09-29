package com.ticketing.payment.application.port.out;

/** 멱등 장부(processed_event) — 같은 쪽지 번호는 두 번 기록될 수 없다. */
public interface ProcessedEventStore {

    /** 이미 처리한 쪽지인가 (부작용 없는 조회) */
    boolean alreadyProcessed(String consumer, String eventId);

    /** 장부에 적는다. 처음이면 true, 누가 먼저 적었으면 false — 복합 PK가 판정한다 */
    boolean markProcessed(String consumer, String eventId);
}
