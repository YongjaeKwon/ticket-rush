package com.ticketing.reservation.adapter.out.persistence;

import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.domain.ReservationHeld;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "DB에 먼저 적고 나중에 보낸다" — 이벤트를 outbox 테이블에 기록한다.
 * 예매 저장과 같은 트랜잭션이라, 커밋되면 이벤트도 반드시 함께 남고
 * 롤백되면 이벤트도 함께 사라진다. 실제 전송(릴레이 → Kafka)은 3단계.
 * 봉투 형식은 ARCHITECTURE 4-5: eventId·eventType·version·occurredAt·aggregateId·payload.
 */
@Component
class OutboxEventPublisherAdapter implements EventPublisher {

    private final JdbcClient jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    OutboxEventPublisherAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void publish(ReservationHeld event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("eventType", "ReservationHeld");
        envelope.put("version", 1);
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("aggregateId", String.valueOf(event.reservationId()));
        envelope.put("payload", Map.of(
                "reservationId", event.reservationId(),
                "scheduleId", event.scheduleId(),
                "seatId", event.seatId(),
                "userId", event.userId(),
                "expiresAt", event.expiresAt().toString()));

        jdbc.sql("""
                        INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
                        VALUES (:aggregateType, :aggregateId, :eventType, :payload, :createdAt)
                        """)
                .param("aggregateType", "RESERVATION")
                .param("aggregateId", String.valueOf(event.reservationId()))
                .param("eventType", "ReservationHeld")
                .param("payload", json.writeValueAsString(envelope))
                .param("createdAt", event.occurredAt())
                .update();
    }
}
