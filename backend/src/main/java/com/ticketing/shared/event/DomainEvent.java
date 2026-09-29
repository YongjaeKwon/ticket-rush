package com.ticketing.shared.event;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 도메인 이벤트 공통 계약. 순수 자바 — 직렬화 방식(JSON, Outbox)은 어댑터가 정한다.
 * 이벤트 이름은 과거형: 이미 일어난 사실이다.
 * (1단계엔 reservation 안에 있었지만, 3단계부터 payment·queue도 발행하므로 shared로 올렸다)
 */
public interface DomainEvent {

    String eventType();

    /** 이벤트의 주인공 애그리거트 id — 봉투의 aggregateId가 된다 */
    long aggregateId();

    LocalDateTime occurredAt();

    /** 파티션 키 규약: 반드시 scheduleId를 포함한다 (Topics 참고) */
    Map<String, Object> payload();
}
