package com.ticketing.reservation;

import com.ticketing.reservation.application.port.in.HoldSeatUseCase;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase.HoldResult;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase.HoldSeatCommand;
import com.ticketing.shared.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 실물 MySQL + Redis로 홀드 유스케이스의 성공·충돌·되돌리기를 검증한다. */
@SpringBootTest
@Testcontainers
class HoldSeatIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    HoldSeatUseCase holdSeat;

    @Autowired
    com.ticketing.queue.application.port.out.AdmissionTokenIssuer tokenIssuer;

    private String admissionFor(String userId) {
        return tokenIssuer.issue(1L, userId);
    }

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM reservation").update();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void 홀드에_성공하면_Redis_키와_DB_HELD_행과_Outbox_이벤트가_함께_남는다() {
        HoldResult result = holdSeat.hold(new HoldSeatCommand(1L, 17L, "user-1", admissionFor("user-1")));

        assertThat(result.reservationId()).isPositive();

        // Redis: 선점 키 존재 + TTL 동작 중 + 값은 소유자
        assertThat(redisTemplate.opsForValue().get("hold:1:17")).isEqualTo("user-1");
        assertThat(redisTemplate.getExpire("hold:1:17")).isBetween(240L, 300L);   // 초 단위 TTL

        // DB: HELD 행 하나
        String status = jdbc.sql("SELECT status FROM reservation WHERE id = :id")
                .param("id", result.reservationId()).query(String.class).single();
        assertThat(status).isEqualTo("HELD");

        // Outbox: ReservationHeld 한 건, 아직 발행 전(published_at IS NULL)
        // MySQL JSON 컬럼은 키 순서·공백을 재정렬하므로 문자열 비교 대신 파싱해서 확인한다
        String payload = jdbc.sql("SELECT payload FROM outbox WHERE event_type = 'ReservationHeld' AND published_at IS NULL")
                .query(String.class).single();
        var envelope = tools.jackson.databind.json.JsonMapper.builder().build().readTree(payload);
        assertThat(envelope.get("eventType").asText()).isEqualTo("ReservationHeld");
        assertThat(envelope.get("payload").get("seatId").asLong()).isEqualTo(17L);
        assertThat(envelope.get("eventId").asText()).isNotBlank();
    }

    @Test
    void 같은_좌석을_두_번_홀드하면_두_번째는_SEAT_ALREADY_HELD다() {
        holdSeat.hold(new HoldSeatCommand(1L, 18L, "user-1", admissionFor("user-1")));

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> holdSeat.hold(new HoldSeatCommand(1L, 18L, "user-2", admissionFor("user-2"))));

        assertThat(e.code()).isEqualTo("SEAT_ALREADY_HELD");
        // 첫 사용자의 선점과 예매는 그대로다
        assertThat(redisTemplate.opsForValue().get("hold:1:18")).isEqualTo("user-1");
        Long count = jdbc.sql("SELECT COUNT(*) FROM reservation WHERE seat_id = 18")
                .query(Long.class).single();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void 다른_좌석은_서로_영향_없이_홀드된다() {
        holdSeat.hold(new HoldSeatCommand(1L, 20L, "user-1", admissionFor("user-1")));
        HoldResult second = holdSeat.hold(new HoldSeatCommand(1L, 21L, "user-2", admissionFor("user-2")));

        assertThat(second.reservationId()).isPositive();
    }

    @Test
    void DB_저장이_실패하면_Redis_선점을_즉시_되돌린다() {
        // user_id 컬럼은 VARCHAR(64) — 65자를 넣어 DB 단계 실패를 일부러 만든다
        String tooLongUserId = "u".repeat(65);

        catchThrowableOfType(RuntimeException.class,
                () -> holdSeat.hold(new HoldSeatCommand(1L, 19L, tooLongUserId, admissionFor(tooLongUserId))));

        // Redis 키가 남아 있지 않다 → 좌석은 곧바로 다른 사람이 잡을 수 있다
        assertThat(redisTemplate.hasKey("hold:1:19")).isFalse();
        // DB에도 아무것도 남지 않았다 (트랜잭션 롤백)
        Long count = jdbc.sql("SELECT COUNT(*) FROM reservation WHERE seat_id = 19")
                .query(Long.class).single();
        assertThat(count).isZero();

        // 그리고 실제로 다른 사용자가 즉시 홀드할 수 있다
        HoldResult retry = holdSeat.hold(new HoldSeatCommand(1L, 19L, "user-2", admissionFor("user-2")));
        assertThat(retry.reservationId()).isPositive();
    }
}
