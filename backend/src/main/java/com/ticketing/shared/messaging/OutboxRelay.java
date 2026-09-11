package com.ticketing.shared.messaging;

import com.ticketing.shared.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 릴레이 — "서랍을 비우는 집배원" (ADR 0007).
 * 1초마다 아직 발행되지 않은 봉투를 오래된 것부터 꺼내, 순서대로 Kafka에 싣고,
 * 성공한 것만 published_at을 찍는다.
 *
 * 규칙 세 가지:
 * - 저장된 봉투를 그대로 보낸다. 다시 만들지 않는다 — eventId가 바뀌면 컨슈머의 중복 제거가 무너진다.
 * - 파티션 키는 payload의 scheduleId (Topics 규약, 서랍 입구에서 강제). 그래도 없으면 틱을 멈추고
 *   에러를 남긴다 — 사람이 그 행을 고칠 때까지 발행 전체가 선다. 이 반경은 ADR 0007에 적어 두었다.
 * - 전송 하나가 실패하면 건너뛰지 않고 틱을 멈춘다(같은 애그리거트의 인과 순서 유지).
 *   전송과 published_at 기록 사이에 죽거나, 타임아웃된 전송이 뒤늦게 도착하면
 *   같은 봉투가 두 번 나간다 — 그래서 컨슈머가 멱등이어야 한다 (at-least-once).
 */
@Component
@ConditionalOnProperty("outbox.relay.enabled")
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    /** aggregate_type → 토픽. 발행하는 모듈이 늘면 여기에 한 줄 */
    private static final Map<String, String> TOPIC_BY_AGGREGATE = Map.of(
            "RESERVATION", Topics.RESERVATION_EVENTS,
            "PAYMENT", Topics.PAYMENT_EVENTS,
            "QUEUE", Topics.QUEUE_EVENTS);

    private final JdbcClient jdbc;
    // 자동 설정 빈의 제네릭이 <Object, Object>다 — 직렬화기는 yml에서 String으로 고정했다
    private final KafkaTemplate<Object, Object> kafka;
    private final Clock clock;
    private final int batchSize;
    private final JsonMapper json = JsonMapper.builder().build();

    OutboxRelay(JdbcClient jdbc, KafkaTemplate<Object, Object> kafka, Clock clock,
                @Value("${outbox.relay.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    record PendingRow(long id, String aggregateType, String payload) {
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:1000}")
    void relayOnce() {
        List<PendingRow> pending = jdbc.sql("""
                        SELECT id, aggregate_type, payload FROM outbox
                        WHERE published_at IS NULL
                        ORDER BY created_at, id
                        LIMIT :limit
                        """)
                .param("limit", batchSize)
                .query((rs, i) -> new PendingRow(rs.getLong("id"),
                        rs.getString("aggregate_type"), rs.getString("payload")))
                .list();

        for (PendingRow row : pending) {
            if (!sendAndMark(row)) {
                return; // 다음 틱에 같은 자리부터 — 순서를 지키려고 건너뛰지 않는다
            }
        }
    }

    private boolean sendAndMark(PendingRow row) {
        String topic = TOPIC_BY_AGGREGATE.get(row.aggregateType());
        if (topic == null) {
            log.error("outbox 릴레이 중단 — 모르는 aggregate_type: {} (id={})", row.aggregateType(), row.id());
            return false;
        }
        String key = partitionKey(row);
        if (key == null) {
            return false;
        }
        try {
            kafka.send(topic, key, row.payload()).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Kafka가 없거나 잠시 아픈 경우 — 봉투는 서랍에 그대로, 다음 틱에 재시도
            log.warn("outbox 발행 실패, 다음 틱에 재시도 (id={}): {}", row.id(), e.getMessage());
            return false;
        }
        jdbc.sql("UPDATE outbox SET published_at = :now WHERE id = :id")
                .param("now", LocalDateTime.now(clock))
                .param("id", row.id())
                .update();
        return true;
    }

    private String partitionKey(PendingRow row) {
        try {
            JsonNode scheduleId = json.readTree(row.payload()).path("payload").path("scheduleId");
            if (!scheduleId.isNumber()) {
                log.error("outbox 릴레이 중단 — payload에 scheduleId가 없다 (id={}, Topics 규약 위반)", row.id());
                return null;
            }
            return Topics.partitionKey(scheduleId.asLong());
        } catch (Exception e) {
            log.error("outbox 릴레이 중단 — 봉투 JSON을 읽을 수 없다 (id={})", row.id(), e);
            return null;
        }
    }
}
