package com.ticketing.reservation;

import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase;
import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResult;
import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResultCommand;
import com.ticketing.reservation.application.port.in.CancelReservationUseCase;
import com.ticketing.reservation.application.port.in.CancelReservationUseCase.CancelCommand;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase.ConfirmCommand;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase.ConfirmResult;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase.HoldSeatCommand;
import com.ticketing.reservation.domain.PaymentStatus;
import com.ticketing.reservation.domain.ReservationStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 확정 요청(202 접수)과 그 뒤 승인 반영을 실물 DB·Redis로 검증한다 (ADR 0009).
 * Kafka 왕복 없이 유스케이스를 직접 부른다 — 결과 쪽지의 전 구간 왕복은 ConfirmFlowIntegrationTest 몫.
 */
@SpringBootTest(properties = {"hold-expiry.enabled=false", "outbox.relay.enabled=false", "kafka.consumers.enabled=false"})
@Testcontainers
class ConfirmReservationIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    HoldSeatUseCase holdSeat;

    @Autowired
    ConfirmReservationUseCase confirmReservation;

    @Autowired
    CancelReservationUseCase cancelReservation;

    @Autowired
    ApplyPaymentResultUseCase applyPaymentResult;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM confirmed_seat").update();
        jdbc.sql("DELETE FROM processed_event").update();
        jdbc.sql("DELETE FROM reservation").update();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Autowired
    com.ticketing.queue.application.port.out.AdmissionTokenIssuer tokenIssuer;

    private long holdSeatAs(String userId, long seatId) {
        return holdSeat.hold(new HoldSeatCommand(1L, seatId, userId,
                tokenIssuer.issue(1L, userId))).reservationId();
    }

    private Long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void 확정_요청은_결제_요청_쪽지를_서랍에_넣고_HELD_REQUESTED로_접수한다() {
        long reservationId = holdSeatAs("user-1", 30L);

        ConfirmResult result = confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1"));

        // 접수이지 판정이 아니다 — 예매는 아직 HELD, 좌석·홀드도 그대로
        assertThat(result.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(jdbc.sql("SELECT CONCAT(status, '|', payment_status) FROM reservation WHERE id = " + reservationId)
                .query(String.class).single()).isEqualTo("HELD|REQUESTED");
        assertThat(count("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 30")).isZero();
        assertThat(redisTemplate.hasKey("hold:1:30")).isTrue();

        // 쪽지는 reservation.events로 가고, 금액은 서버가 정한다(클라이언트가 보낸 값을 믿지 않는다)
        assertThat(jdbc.sql("""
                        SELECT CONCAT(aggregate_type, '|',
                               JSON_EXTRACT(payload, '$.payload.reservationId'), '|',
                               JSON_EXTRACT(payload, '$.payload.scheduleId'), '|',
                               JSON_UNQUOTE(JSON_EXTRACT(payload, '$.payload.userId')), '|',
                               JSON_EXTRACT(payload, '$.payload.amount'))
                        FROM outbox WHERE event_type = 'PaymentRequested'
                        """).query(String.class).single())
                .isEqualTo("RESERVATION|" + reservationId + "|1|user-1|134000");
    }

    @Test
    void 결제_중에_다시_확정하면_PAYMENT_IN_PROGRESS_쪽지는_한_장뿐이다() {
        long reservationId = holdSeatAs("user-1", 34L);
        confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1"));

        // 다른 탭·새 접수번호로 온 두 번째 확정 — 통과시키면 결제가 두 번 승인된다
        ApiException e = catchThrowableOfType(ApiException.class,
                () -> confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1")));

        assertThat(e.code()).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentRequested'")).isEqualTo(1L);
    }

    @Test
    void 결제_중에는_취소할_수_없다() {
        long reservationId = holdSeatAs("user-1", 35L);
        confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1"));

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> cancelReservation.cancel(new CancelCommand(reservationId, "user-1")));

        assertThat(e.code()).isEqualTo("PAYMENT_IN_PROGRESS");
        assertThat(redisTemplate.hasKey("hold:1:35")).isTrue();   // 좌석도 붙잡은 그대로
    }

    @Test
    void 거절된_뒤에는_다시_확정을_요청할_수_있다() {
        long reservationId = holdSeatAs("user-1", 36L);
        confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1"));
        applyPaymentResult.apply(new PaymentResultCommand("decline-1", reservationId, PaymentResult.DECLINED, null));

        ConfirmResult retry = confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1"));

        assertThat(retry.paymentStatus()).isEqualTo(PaymentStatus.REQUESTED);
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentRequested'")).isEqualTo(2L);
    }

    @Test
    void 만료된_홀드는_결제_요청_전에_HOLD_EXPIRED로_거절된다() {
        long reservationId = holdSeatAs("user-1", 31L);
        jdbc.sql("UPDATE reservation SET expires_at = expires_at - INTERVAL 10 MINUTE WHERE id = :id")
                .param("id", reservationId).update();

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1")));

        assertThat(e.code()).isEqualTo("HOLD_EXPIRED");
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = " + reservationId)
                .query(String.class).single()).isEqualTo("HELD");   // 만료 전이는 스케줄러 몫
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentRequested'")).isZero();
    }

    @Test
    void 같은_좌석의_두_번째_승인은_UNIQUE가_막는다() {
        // 같은 좌석에 대한 예매 두 건 — 두 번째는 Redis 키를 지워 홀드를 흉내 낸다(Redis가 죽은 상황)
        long first = holdSeatAs("user-1", 32L);
        redisTemplate.delete("hold:1:32");
        long second = holdSeatAs("user-2", 32L);
        confirmReservation.confirm(new ConfirmCommand(first, "user-1"));
        confirmReservation.confirm(new ConfirmCommand(second, "user-2"));

        applyPaymentResult.apply(new PaymentResultCommand("approve-1", first, PaymentResult.APPROVED, "mock-tx-1"));
        applyPaymentResult.apply(new PaymentResultCommand("approve-2", second, PaymentResult.APPROVED, "mock-tx-2"));

        // 두 번째 승인은 확정되지 못한다 — 이중 확정은 없다. 그 예매는 결론(EXPIRED + APPROVED)으로
        // 닫혀 조회에 드러난다. REQUESTED로 남으면 재결제·취소가 막힌 채 결론이 안 보인다(환불 보상은 backlog)
        assertThat(count("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 32")).isEqualTo(1L);
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = " + first)
                .query(String.class).single()).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT CONCAT(status, '|', payment_status, '|', payment_tx_id) FROM reservation WHERE id = " + second)
                .query(String.class).single()).isEqualTo("EXPIRED|APPROVED|mock-tx-2");
    }

    @Test
    void 확정된_예매는_다시_결제를_요청할_수_없다() {
        long reservationId = holdSeatAs("user-1", 37L);
        confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1"));
        applyPaymentResult.apply(new PaymentResultCommand("approve-37", reservationId, PaymentResult.APPROVED, "mock-tx"));

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1")));

        assertThat(e.code()).isEqualTo("INVALID_RESERVATION_STATE");
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentRequested'")).isEqualTo(1L);
    }

    @Test
    void 남의_예매는_확정할_수_없다() {
        long reservationId = holdSeatAs("user-1", 33L);

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> confirmReservation.confirm(new ConfirmCommand(reservationId, "user-2")));

        assertThat(e.code()).isEqualTo("RESERVATION_NOT_OWNED");
    }
}
