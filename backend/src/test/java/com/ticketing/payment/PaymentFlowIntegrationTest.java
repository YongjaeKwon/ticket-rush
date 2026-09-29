package com.ticketing.payment;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 쪽지 한 장의 왕복 전체를 실물로 검증한다:
 * reservation.events에 PaymentRequested 투입 → 컨슈머가 결제 → payment 행 기록 →
 * 결과 봉투가 서랍(outbox) → 릴레이 → payment.events 도착.
 * 같은 쪽지를 한 번 더 넣어 멱등(기록·발행 각 1건)도 확인한다.
 */
@SpringBootTest(properties = {
        "outbox.relay.poll-interval-ms=200",
        // 이 테스트는 승인 왕복을 검증한다 — mock PG의 기본값에 기대지 않고 전제를 박아 둔다
        "payment.mock.failure-rate=0.0"})
@Testcontainers
class PaymentFlowIntegrationTest {

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

    @Test
    void 결제_요청_쪽지가_기록과_결과_쪽지로_돌아온다_두_번_넣어도_한_번만() {
        String eventId = "aaaaaaaa-bbbb-cccc-dddd-eeeeffff0001";
        String envelope = """
                {"eventId":"%s","eventType":"PaymentRequested","version":1,\
                "occurredAt":"2026-09-11T05:00:00","aggregateId":"501",\
                "payload":{"reservationId":501,"scheduleId":1,"userId":"u-pay","amount":134000}}"""
                .formatted(eventId);

        send("reservation.events", "1", envelope);

        // ① 결제가 기록된다 (mock PG 승인)
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            List<String> rows = jdbc.sql("SELECT CONCAT(status, '|', pg_tx_id) FROM payment WHERE reservation_id = 501")
                    .query(String.class).list();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)).startsWith("APPROVED|mock-");
        });

        // ② 결과 봉투가 릴레이를 타고 payment.events에 도착한다 — 키는 scheduleId
        // 주의: MySQL JSON 컬럼은 키 순서·공백을 정규화해 돌려준다 — 문자열 비교 말고 파싱으로 찾는다
        try (KafkaConsumer<String, String> consumer = consumerFrom("payment.events")) {
            ConsumerRecord<String, String> record = awaitMatching(consumer,
                    r -> json.readTree(r.value()).path("payload").path("reservationId").asLong() == 501);
            assertThat(record.key()).isEqualTo("1");
            JsonNode sent = json.readTree(record.value());
            assertThat(sent.get("eventType").asString()).isEqualTo("PaymentApproved");
            assertThat(sent.get("payload").get("pgTxId").asString()).startsWith("mock-");
            // 결과 쪽지의 번호는 요청 쪽지와 다르다 — 새 사실에는 새 번호
            assertThat(sent.get("eventId").asString()).isNotEqualTo(eventId);
        }

        // ③ 같은 쪽지를 한 번 더 넣고, 뒤이어 다른 쪽지(파수꾼)를 같은 키로 보낸다.
        //    같은 파티션은 순서대로 처리되므로 파수꾼의 행이 생겼다면 중복도 이미 컨슈머를 지났다 —
        //    "아직 안 왔을 뿐"인데 통과하는 공허한 검증을 막는 장치
        send("reservation.events", "1", envelope);
        send("reservation.events", "1", """
                {"eventId":"aaaaaaaa-bbbb-cccc-dddd-eeeeffff0002","eventType":"PaymentRequested",\
                "version":1,"occurredAt":"2026-09-11T05:00:10","aggregateId":"502",\
                "payload":{"reservationId":502,"scheduleId":1,"userId":"u-pay","amount":50000}}""");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = 502")).isEqualTo(1L));

        // 중복은 장부(processed_event)가 막았다 — 501의 기록·발행·장부 모두 1건 그대로
        assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = 501")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE aggregate_type = 'PAYMENT' AND aggregate_id = '501'"))
                .isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE consumer = 'payment.payment-requested'"
                + " AND event_id = '" + eventId + "'")).isEqualTo(1L);
    }

    private Long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void send(String topic, String key, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value));
            producer.flush();
        }
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

    private ConsumerRecord<String, String> awaitMatching(
            KafkaConsumer<String, String> consumer,
            java.util.function.Predicate<ConsumerRecord<String, String>> mine) {
        List<String> seen = new java.util.ArrayList<>();
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, String> r : polled) {
                if (mine.test(r)) {
                    return r;
                }
                seen.add(r.key() + " → " + r.value());
            }
        }
        // 실패 원인을 그 자리에서 보이게 — 서랍 상태와 실제 도착한 레코드를 함께 남긴다
        List<String> outboxRows = jdbc.sql(
                        "SELECT CONCAT(id, ' ', aggregate_type, ' ', event_type, ' published=', COALESCE(CAST(published_at AS CHAR), 'NULL')) FROM outbox")
                .query(String.class).list();
        throw new AssertionError("기다리던 봉투를 받지 못했다.\n서랍: " + outboxRows + "\n받은 레코드: " + seen);
    }
}
