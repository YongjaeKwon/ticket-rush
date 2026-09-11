package com.ticketing.shared.messaging;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 릴레이를 실물로 검증한다: 실제 MySQL의 outbox 행 → 실제 Kafka의 메시지.
 * 봉투가 그대로 실리는지(eventId 보존), 파티션 키가 scheduleId인지, 발행 표시가 찍히는지.
 */
@SpringBootTest(properties = "outbox.relay.poll-interval-ms=200") // 테스트는 빠르게 돈다
@Testcontainers
class OutboxRelayIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Autowired
    JdbcClient jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void cleanOutbox() {
        jdbc.sql("DELETE FROM outbox").update();
    }

    @Test
    void 서랍의_봉투가_그대로_Kafka에_실리고_발행_표시가_찍힌다() {
        // 저장 시점에 만들어진 봉투를 흉내 내 서랍에 직접 넣는다 — eventId가 그대로 나가야 한다
        String envelope = """
                {"eventId":"11111111-2222-3333-4444-555555555555","eventType":"ReservationHeld",\
                "version":1,"occurredAt":"2026-09-11T03:00:00","aggregateId":"77",\
                "payload":{"reservationId":77,"scheduleId":1,"seatId":42,"userId":"u-1","expiresAt":"2026-09-11T03:05:00"}}""";
        insertOutbox("RESERVATION", "77", "ReservationHeld", envelope, "2026-09-11 03:00:00");

        try (KafkaConsumer<String, String> consumer = consumerFrom("reservation.events")) {
            // 토픽에는 다른 테스트의 봉투도 남아 있다 — 내 eventId만 골라 받는다
            ConsumerRecord<String, String> record = awaitMatching(consumer, 1,
                    r -> r.value().contains("11111111-2222-3333-4444-555555555555")).get(0);

            assertThat(record.key()).isEqualTo("1"); // 파티션 키 = scheduleId
            JsonNode sent = json.readTree(record.value());
            assertThat(sent.get("eventId").asString()).isEqualTo("11111111-2222-3333-4444-555555555555");
            assertThat(sent.get("eventType").asString()).isEqualTo("ReservationHeld");
            assertThat(sent.get("payload").get("seatId").asLong()).isEqualTo(42L);
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")
                        .query(Long.class).single()).isZero());
    }

    @Test
    void 같은_회차의_봉투_여러_장은_쌓인_순서대로_같은_파티션에_실린다() {
        for (int i = 1; i <= 3; i++) {
            String envelope = """
                    {"eventId":"00000000-0000-0000-0000-00000000000%d","eventType":"ReservationHeld",\
                    "version":1,"occurredAt":"2026-09-11T03:00:0%d","aggregateId":"%d",\
                    "payload":{"reservationId":%d,"scheduleId":1,"seatId":%d}}"""
                    .formatted(i, i, i, i, i);
            insertOutbox("RESERVATION", String.valueOf(i), "ReservationHeld", envelope, "2026-09-11 03:00:0" + i);
        }

        try (KafkaConsumer<String, String> consumer = consumerFrom("reservation.events")) {
            List<ConsumerRecord<String, String>> records = awaitMatching(consumer, 3,
                    r -> r.value().contains("00000000-0000-0000-0000-00000000000"));

            assertThat(records).extracting(r -> json.readTree(r.value()).get("aggregateId").asString())
                    .containsExactly("1", "2", "3"); // created_at 순서 그대로
            assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.get(0).partition());
            assertThat(records).extracting(ConsumerRecord::key).containsOnly("1");
        }
    }

    @Test
    void scheduleId가_없는_봉투는_발행되지_않고_서랍에_남는다() {
        // Topics 규약 위반 — 키를 만들 수 없으니 릴레이가 멈추고, 사람이 로그로 알아챈다
        String broken = """
                {"eventId":"99999999-0000-0000-0000-000000000000","eventType":"Broken",\
                "version":1,"occurredAt":"2026-09-11T03:00:00","aggregateId":"9","payload":{"seatId":9}}""";
        insertOutbox("RESERVATION", "9", "Broken", broken, "2026-09-11 03:00:00");

        // 몇 틱을 기다려도 발행 표시가 찍히지 않는다
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL")
                        .query(Long.class).single()).isEqualTo(1L));
    }

    private void insertOutbox(String aggregateType, String aggregateId, String eventType,
                              String payload, String createdAt) {
        jdbc.sql("""
                        INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload, created_at)
                        VALUES (:type, :id, :event, :payload, :createdAt)
                        """)
                .param("type", aggregateType).param("id", aggregateId)
                .param("event", eventType).param("payload", payload)
                .param("createdAt", createdAt)
                .update();
    }

    private KafkaConsumer<String, String> consumerFrom(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    private List<ConsumerRecord<String, String>> awaitMatching(
            KafkaConsumer<String, String> consumer, int count,
            java.util.function.Predicate<ConsumerRecord<String, String>> mine) {
        List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + 20_000;
        while (collected.size() < count && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
            polled.forEach(r -> {
                if (mine.test(r)) {
                    collected.add(r);
                }
            });
        }
        assertThat(collected).hasSize(count);
        return collected;
    }
}
