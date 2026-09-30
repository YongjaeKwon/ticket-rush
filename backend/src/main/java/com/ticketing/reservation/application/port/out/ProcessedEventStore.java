package com.ticketing.reservation.application.port.out;

/** 멱등 장부 — 같은 쪽지(event_id)를 이 컨슈머가 두 번 처리하지 않게 막는다. */
public interface ProcessedEventStore {

    /** 부작용 없는 선확인. */
    boolean alreadyProcessed(String consumer, String eventId);

    /** 처리와 같은 트랜잭션에서 기록. 누가 먼저 적었으면 false — 호출자가 전부 롤백한다. */
    boolean markProcessed(String consumer, String eventId);
}
