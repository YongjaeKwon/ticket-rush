package com.ticketing.reservation.domain;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 도메인 이벤트 공통 계약. 순수 자바 — 직렬화 방식(JSON, Outbox)은 어댑터가 정한다.
 * 이벤트 이름은 과거형: 이미 일어난 사실이다.
 */
public interface DomainEvent {

    String eventType();

    long aggregateId();

    LocalDateTime occurredAt();

    Map<String, Object> payload();
}
