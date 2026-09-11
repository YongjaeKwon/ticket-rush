package com.ticketing.reservation.adapter.out.persistence;

import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.domain.DomainEvent;
import com.ticketing.shared.event.EventEnvelope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * "DB에 먼저 적고 나중에 보낸다" — 이벤트를 봉투(EventEnvelope)에 넣어 outbox 테이블에 기록한다.
 * 예매 저장과 같은 트랜잭션이라, 커밋되면 이벤트도 반드시 함께 남고
 * 롤백되면 이벤트도 함께 사라진다. 실제 전송(릴레이 → Kafka)은 다음 단위 —
 * 릴레이는 여기 저장된 봉투를 그대로 보내야 한다(eventId 재발급 금지).
 */
@Component
class OutboxEventPublisherAdapter implements EventPublisher {

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    OutboxEventPublisherAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void publish(DomainEvent event) {
        // Topics 규약: 모든 payload는 scheduleId(파티션 키)를 포함한다.
        // 여기서 막아야 릴레이가 보낼 수 없는 봉투(poison row)가 서랍에 들어가지 않는다
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
                .param("aggregateType", "RESERVATION")
                .param("aggregateId", envelope.aggregateId())
                .param("eventType", envelope.eventType())
                .param("payload", json.writeValueAsString(envelope))
                .param("createdAt", envelope.occurredAt())
                .update();
    }
}
