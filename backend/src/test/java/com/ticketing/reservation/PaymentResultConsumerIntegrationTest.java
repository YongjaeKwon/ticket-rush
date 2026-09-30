package com.ticketing.reservation;

import com.ticketing.reservation.application.port.out.ReservationRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
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

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 결제 결과 쪽지가 예매 상태로 이어지는 소비 경로를 실물로 검증한다:
 * payment.events에 봉투 투입 → 컨슈머 → 상태 전이 + confirmed_seat + outbox 이벤트.
 * 중복 검증은 같은 파티션의 순서 보장을 이용한 파수꾼(sentinel) 쪽지로 결정적으로 한다.
 */
@SpringBootTest(properties = {
        "outbox.relay.enabled=false",   // 관심사는 컨슈머 — 릴레이는 끈다
        "hold-expiry.enabled=false"})   // 만료 스케줄러도 — HELD 유지 단언을 건드릴 수 있는 유일한 손
@Testcontainers
class PaymentResultConsumerIntegrationTest {

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
    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Autowired
    com.ticketing.reservation.application.port.out.SeatHoldStore seatHoldStore;
    @Autowired
    ReservationRepository realRepository;
    @Autowired
    com.ticketing.reservation.application.port.out.ProcessedEventStore processedEvents;
    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    @Test
    void 승인_쪽지는_확정으로_이어지고_같은_쪽지를_두_번_넣어도_한_번만() {
        long r1 = insertHeldReservation(17L, "u-consume-1");
        String eventId = "bbbbbbbb-cccc-dddd-eeee-ffff00000001";
        String approved = paymentEnvelope(eventId, "PaymentApproved", r1);

        send(approved);

        // ① 확정 전이 + 최종 방어선 기록 + 다음 소비자(SSE 등)를 위한 outbox 이벤트
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = " + r1)
                    .query(String.class).single()).isEqualTo("CONFIRMED");
        });
        assertThat(count("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 17 AND reservation_id = " + r1))
                .isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'ReservationConfirmed'"
                + " AND aggregate_id = '" + r1 + "'")).isEqualTo(1L);

        // ② 같은 쪽지 재주입 + 파수꾼(다른 예매의 거절 쪽지, 같은 키) — 파수꾼 처리 = 중복도 지나갔다.
        //    전제: 같은 키("1")라 같은 파티션 + 기본 blocking 에러 처리(재시도 토픽 없음)라 파티션 순서 = 처리 순서.
        //    체크리스트 8에서 non-blocking retry(@RetryableTopic)를 고르면 이 전제가 깨질 수 있다 — 그때 재검토
        long r2 = insertHeldReservation(18L, "u-consume-2");
        String sentinelId = "bbbbbbbb-cccc-dddd-eeee-ffff00000002";
        send(approved);
        send(paymentEnvelope(sentinelId, "PaymentDeclined", r2));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id = '" + sentinelId + "'"))
                        .isEqualTo(1L));

        // 중복은 장부가 막았다 — 좌석 기록·이벤트·장부 모두 1건 그대로
        assertThat(count("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 17")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'ReservationConfirmed'"
                + " AND aggregate_id = '" + r1 + "'")).isEqualTo(1L);
        // 이 단언이 멱등 장부 검증의 실질 전부 — 위의 confirmed_seat·outbox 단언은
        // 장부가 없어도 도메인 confirm()의 종결 상태 거부로 통과한다. 지우지 말 것
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id = '" + eventId + "'"))
                .isEqualTo(1L);

        // 파수꾼(거절)은 HELD 유지 — 상태를 건드리지 않는다
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = " + r2)
                .query(String.class).single()).isEqualTo("HELD");
    }

    @Test
    void 실패_쪽지는_홀드를_풀어준다_EXPIRED_되돌리기() {
        long r3 = insertHeldReservation(19L, "u-consume-3");
        // 실물 홀드 키를 심어 커밋 후 해제(비교-삭제)의 배선까지 관측한다
        redisTemplate.opsForValue().set("hold:1:19", "u-consume-3", Duration.ofMinutes(5));

        send(paymentEnvelope("bbbbbbbb-cccc-dddd-eeee-ffff00000003", "PaymentFailed", r3));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = " + r3)
                    .query(String.class).single()).isEqualTo("EXPIRED");
            // release는 커밋 뒤라 같은 await 안에서 본다 — 키가 사라져야 재홀드(SET NX)가 가능해진다
            assertThat(redisTemplate.hasKey("hold:1:19")).isFalse();
        });
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'ReservationExpired'"
                + " AND aggregate_id = '" + r3 + "'")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 19")).isEqualTo(0L);
    }

    @Test
    void 남의_홀드_키는_해제가_지우지_않는다_비교_삭제() {
        // 만료 직후 다른 사용자가 새로 잡은 키를, 뒤늦은 해제가 지우면 안 된다 (Lua compare-and-delete)
        redisTemplate.opsForValue().set("hold:1:97", "new-owner", Duration.ofMinutes(5));

        seatHoldStore.release(1L, 97L, "old-owner");

        assertThat(redisTemplate.opsForValue().get("hold:1:97")).isEqualTo("new-owner");
    }

    @Test
    void 기록이_실패하면_좌석_기록과_장부가_함께_되돌아간다() {
        // ApplyPaymentResultService 고유의 조합(장부+confirmed_seat+save 한 트랜잭션)을 실물로 증명 —
        // registerConfirmedSeat 성공분까지 롤백돼야 좌석이 유령 봉인되지 않는다
        long r4 = insertHeldReservation(20L, "u-atomic");
        ReservationRepository throwingSave = new ReservationRepository() {
            @Override public com.ticketing.reservation.domain.Reservation save(
                    com.ticketing.reservation.domain.Reservation r) {
                throw new IllegalStateException("save 실패");
            }
            @Override public java.util.Optional<com.ticketing.reservation.domain.Reservation> findById(long id) {
                return realRepository.findById(id);
            }
            @Override public java.util.List<com.ticketing.reservation.domain.Reservation> findExpiredHeld(
                    java.time.LocalDateTime now) {
                return java.util.List.of();
            }
            @Override public boolean registerConfirmedSeat(long scheduleId, long seatId, long reservationId) {
                return realRepository.registerConfirmedSeat(scheduleId, seatId, reservationId);
            }
        };
        var noopHolds = new com.ticketing.reservation.application.port.out.SeatHoldStore() {
            @Override public boolean tryHold(long s, long seat, String u, Duration ttl) { return true; }
            @Override public void release(long s, long seat, String u) { }
        };
        var service = new com.ticketing.reservation.application.service.ApplyPaymentResultService(
                throwingSave, processedEvents, noopHolds, e -> { },
                new org.springframework.transaction.support.TransactionTemplate(txManager),
                java.time.Clock.systemUTC());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.apply(
                        new com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResultCommand(
                                "atomic-consume-1", r4, com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResult.APPROVED)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 20")).isEqualTo(0L);
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id = 'atomic-consume-1'")).isEqualTo(0L);
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = " + r4)
                .query(String.class).single()).isEqualTo("HELD");
    }

    /** HELD 예매를 DB에 직접 심는다 — 홀드 API를 거치지 않아 테스트가 소비 경로만 본다. */
    private long insertHeldReservation(long seatId, String userId) {
        jdbc.sql("""
                        INSERT INTO reservation (schedule_id, seat_id, user_id, status, expires_at, version, created_at)
                        VALUES (1, :seatId, :userId, 'HELD', DATE_ADD(UTC_TIMESTAMP(), INTERVAL 5 MINUTE), 0, UTC_TIMESTAMP())
                        """)
                .param("seatId", seatId).param("userId", userId)
                .update();
        return jdbc.sql("SELECT id FROM reservation WHERE seat_id = :seatId AND user_id = :userId")
                .param("seatId", seatId).param("userId", userId)
                .query(Long.class).single();
    }

    private String paymentEnvelope(String eventId, String eventType, long reservationId) {
        return """
                {"eventId":"%s","eventType":"%s","version":1,\
                "occurredAt":"2026-09-29T05:00:00","aggregateId":"%d",\
                "payload":{"reservationId":%d,"scheduleId":1,"userId":"u","amount":134000}}"""
                .formatted(eventId, eventType, reservationId, reservationId);
    }

    private void send(String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (KafkaProducer<String, String> producer =
                     new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("payment.events", "1", value));
            producer.flush();
        }
    }

    private Long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
