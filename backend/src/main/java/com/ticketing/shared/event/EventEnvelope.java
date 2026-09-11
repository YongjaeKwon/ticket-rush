package com.ticketing.shared.event;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Kafka로 나가는 모든 이벤트의 겉봉투 (ARCHITECTURE 4-5).
 * 컨슈머는 eventId로 중복을 걸러낸다(멱등).
 *
 * 계약 두 줄:
 * - occurredAt은 UTC, 오프셋 없는 ISO-8601 문자열로 직렬화된다 (이 프로젝트의 모든 시각 규약과 동일)
 * - version은 "봉투의 키 구성"이 바뀔 때만 올린다. payload 내용의 진화는 이벤트 타입별 문제라
 *   여기서 다루지 않는다 — 처음 필요해질 때 ADR로 결정한다
 *
 * wrap()은 이벤트가 "기록되는 순간"(Outbox insert) 한 번만 부른다.
 * 릴레이·재시도는 저장된 봉투를 그대로 다시 보내야 한다 — 다시 wrap하면 eventId가 바뀌어
 * 컨슈머의 중복 제거가 무너진다.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int version,
        LocalDateTime occurredAt,
        String aggregateId,
        Map<String, Object> payload
) {

    /** 지금 만드는 봉투의 형식 버전. */
    public static final int CURRENT_VERSION = 1;

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(payload, "payload");
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType은 비울 수 없다");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version은 1부터다: " + version);
        }
        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "payload 값에 null은 담지 않는다 — 값이 없으면 키를 빼라: " + entry.getKey());
            }
        }
        payload = Map.copyOf(payload);
    }

    /** 새 이벤트를 봉투에 넣는다 — eventId는 여기서 발급된다. */
    public static EventEnvelope wrap(String eventType, String aggregateId,
                                     LocalDateTime occurredAt, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), eventType, CURRENT_VERSION,
                occurredAt, aggregateId, payload);
    }
}
