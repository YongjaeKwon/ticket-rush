package com.ticketing.reservation;

import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase.ConfirmCommand;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase.ConfirmResult;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase.HoldSeatCommand;
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

/** 홀드 → 결제 승인 → 확정의 전 구간과 확정이 막혀야 하는 경우들을 실물 DB·Redis로 검증한다. */
@SpringBootTest(properties = "hold-expiry.enabled=false")
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
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM confirmed_seat").update();
        jdbc.sql("DELETE FROM reservation").update();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Autowired
    com.ticketing.queue.application.port.out.AdmissionTokenIssuer tokenIssuer;

    private long holdSeatAs(String userId, long seatId) {
        return holdSeat.hold(new HoldSeatCommand(1L, seatId, userId,
                tokenIssuer.issue(1L, userId))).reservationId();
    }

    @Test
    void 확정에_성공하면_CONFIRMED_확정석_기록_홀드_해제_이벤트까지_한_번에_된다() {
        long reservationId = holdSeatAs("user-1", 30L);

        ConfirmResult result = confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1"));

        assertThat(result.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(result.paymentTransactionId()).startsWith("mock-");

        String status = jdbc.sql("SELECT status FROM reservation WHERE id = :id")
                .param("id", reservationId).query(String.class).single();
        assertThat(status).isEqualTo("CONFIRMED");

        Long confirmedSeat = jdbc.sql(
                        "SELECT reservation_id FROM confirmed_seat WHERE schedule_id = 1 AND seat_id = 30")
                .query(Long.class).single();
        assertThat(confirmedSeat).isEqualTo(reservationId);

        assertThat(redisTemplate.hasKey("hold:1:30")).isFalse();   // 홀드 해제

        Long confirmedEvents = jdbc.sql(
                        "SELECT COUNT(*) FROM outbox WHERE event_type = 'ReservationConfirmed'")
                .query(Long.class).single();
        assertThat(confirmedEvents).isEqualTo(1L);
    }

    @Test
    void 만료된_홀드는_결제_전에_HOLD_EXPIRED로_거절된다() {
        long reservationId = holdSeatAs("user-1", 31L);
        jdbc.sql("UPDATE reservation SET expires_at = expires_at - INTERVAL 10 MINUTE WHERE id = :id")
                .param("id", reservationId).update();

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1")));

        assertThat(e.code()).isEqualTo("HOLD_EXPIRED");
        String status = jdbc.sql("SELECT status FROM reservation WHERE id = :id")
                .param("id", reservationId).query(String.class).single();
        assertThat(status).isEqualTo("HELD");   // 전이는 일어나지 않는다 (만료 처리는 스케줄러 몫)
    }

    @Test
    void 이미_확정된_좌석의_두_번째_확정은_UNIQUE가_막는다() {
        // 같은 좌석에 대한 예매 두 건을 만든다 — 두 번째는 Redis 키를 지워 홀드를 흉내 낸다
        long first = holdSeatAs("user-1", 32L);
        redisTemplate.delete("hold:1:32");                        // Redis가 죽은 상황 재현
        long second = holdSeatAs("user-2", 32L);

        confirmReservation.confirm(new ConfirmCommand(first, "user-1"));
        ApiException e = catchThrowableOfType(ApiException.class,
                () -> confirmReservation.confirm(new ConfirmCommand(second, "user-2")));

        assertThat(e.code()).isEqualTo("SEAT_ALREADY_CONFIRMED");
        // 두 번째 예매는 롤백돼 HELD로 남는다 — 이중 확정은 없다
        String status = jdbc.sql("SELECT status FROM reservation WHERE id = :id")
                .param("id", second).query(String.class).single();
        assertThat(status).isEqualTo("HELD");
        Long confirmedCount = jdbc.sql("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 32")
                .query(Long.class).single();
        assertThat(confirmedCount).isEqualTo(1L);
    }

    @Test
    void 남의_예매는_확정할_수_없다() {
        long reservationId = holdSeatAs("user-1", 33L);

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> confirmReservation.confirm(new ConfirmCommand(reservationId, "user-2")));

        assertThat(e.code()).isEqualTo("RESERVATION_NOT_OWNED");
    }
}
