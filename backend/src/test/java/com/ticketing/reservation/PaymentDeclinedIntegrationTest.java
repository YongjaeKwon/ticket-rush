package com.ticketing.reservation;

import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase.ConfirmCommand;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase.HoldSeatCommand;
import com.ticketing.shared.ApiException;
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

/** Mock PG 실패율을 100%로 놓고 — 거절이 전이가 아님(HELD·홀드 유지)을 검증한다. */
@SpringBootTest(properties = {"payment.mock.failure-rate=1.0", "hold-expiry.enabled=false"})
@Testcontainers
class PaymentDeclinedIntegrationTest {

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

    @Test
    void 결제가_거절되면_HELD와_홀드가_그대로_남아_재시도할_수_있다() {
        long reservationId = holdSeat.hold(new HoldSeatCommand(1L, 40L, "user-1")).reservationId();

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> confirmReservation.confirm(new ConfirmCommand(reservationId, "user-1")));

        assertThat(e.code()).isEqualTo("PAYMENT_DECLINED");
        assertThat(e.status().value()).isEqualTo(402);

        String status = jdbc.sql("SELECT status FROM reservation WHERE id = :id")
                .param("id", reservationId).query(String.class).single();
        assertThat(status).isEqualTo("HELD");                       // 전이 없음
        assertThat(redisTemplate.hasKey("hold:1:40")).isTrue();     // 홀드 유지 — 재시도 가능
        Long confirmedCount = jdbc.sql("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 40")
                .query(Long.class).single();
        assertThat(confirmedCount).isZero();
    }
}
