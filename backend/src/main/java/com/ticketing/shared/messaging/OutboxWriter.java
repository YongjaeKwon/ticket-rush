package com.ticketing.shared.messaging;

import com.ticketing.shared.event.DomainEvent;
import com.ticketing.shared.event.EventEnvelope;
import com.ticketing.shared.event.Topics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서랍(outbox)에 봉투를 넣는 공용 입구 — 호출자의 트랜잭션에 참여한다.
 * 모듈마다 발행 어댑터는 따로 두되(포트는 각자), 실제 기록은 여기 한 곳이라
 * 파티션 키 검증과 봉투 형식이 모듈마다 갈리지 않는다.
 */
@Component
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    OutboxWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void write(String aggregateType, DomainEvent event) {
        // 릴레이가 보낼 수 없는 봉투(poison row)는 서랍에 들어가기 전에 막는다.
        // 모르는 aggregate_type은 릴레이가 틱을 멈추므로(순서 보장) 전 모듈 발행이 선다
        if (!Topics.TOPIC_BY_AGGREGATE.containsKey(aggregateType)) {
            throw new IllegalArgumentException(
                    "모르는 aggregate_type — 릴레이가 보낼 수 없다: " + aggregateType);
        }
        // Topics 규약: 모든 payload는 scheduleId(파티션 키)를 포함한다
        if (!(event.payload().get("scheduleId") instanceof Number)) {
            throw new IllegalArgumentException(
                    "이벤트 payload에 scheduleId가 없다 — 파티션 키 규약 위반: " + event.eventType());
        }
        EventEnvelope envelope = EventEnvelope.wrap(event.eventType(),
                String.valueOf(event.aggregateId()), event.occurredAt(), event.payload());

        jdbc.sql("""
                        INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
                        VALUES (:aggregateType, :aggregateId, :eventType, :payload, :createdAt)
                        """)
                .param("aggregateType", aggregateType)
                .param("aggregateId", envelope.aggregateId())
                .param("eventType", envelope.eventType())
                .param("payload", json.writeValueAsString(envelope))
                .param("createdAt", envelope.occurredAt())
                .update();
    }
}
