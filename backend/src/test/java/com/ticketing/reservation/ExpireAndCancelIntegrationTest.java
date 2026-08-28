package com.ticketing.reservation;

import com.ticketing.reservation.application.port.in.CancelReservationUseCase;
import com.ticketing.reservation.application.port.in.CancelReservationUseCase.CancelCommand;
import com.ticketing.reservation.application.port.in.ExpireHoldUseCase;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 만료 배치(2차 방어)와 사용자 취소를 실물 DB·Redis로 검증한다. */
@SpringBootTest(properties = "hold-expiry.enabled=false")
@Testcontainers
class ExpireAndCancelIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    HoldSeatUseCase holdSeat;

    @Autowired
    ExpireHoldUseCase expireHold;

    @Autowired
    CancelReservationUseCase cancelReservation;

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

    @Test
    void 만료_배치는_시한이_지난_HELD만_EXPIRED로_바꾸고_이벤트를_남긴다() {
        long overdue1 = holdSeat.hold(new HoldSeatCommand(1L, 50L, "user-1")).reservationId();
        long overdue2 = holdSeat.hold(new HoldSeatCommand(1L, 51L, "user-2")).reservationId();
        long alive = holdSeat.hold(new HoldSeatCommand(1L, 52L, "user-3")).reservationId();
        jdbc.sql("UPDATE reservation SET expires_at = expires_at - INTERVAL 10 MINUTE WHERE id IN (:a, :b)")
                .param("a", overdue1).param("b", overdue2).update();

        int expired = expireHold.expireOverdue();

        assertThat(expired).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", overdue1)
                .query(String.class).single()).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", alive)
                .query(String.class).single()).isEqualTo("HELD");   // 살아 있는 홀드는 건드리지 않는다
        assertThat(redisTemplate.hasKey("hold:1:50")).isFalse();
        assertThat(redisTemplate.hasKey("hold:1:52")).isTrue();
        Long events = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE event_type = 'ReservationExpired'")
                .query(Long.class).single();
        assertThat(events).isEqualTo(2L);
    }

    @Test
    void 취소하면_CANCELLED가_되고_좌석이_바로_풀린다() {
        long reservationId = holdSeat.hold(new HoldSeatCommand(1L, 53L, "user-1")).reservationId();

        cancelReservation.cancel(new CancelCommand(reservationId, "user-1"));

        assertThat(jdbc.sql("SELECT status FROM reservation WHERE id = :id").param("id", reservationId)
                .query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(redisTemplate.hasKey("hold:1:53")).isFalse();
        // 좌석이 풀렸으니 다른 사용자가 곧바로 잡을 수 있다
        long retaken = holdSeat.hold(new HoldSeatCommand(1L, 53L, "user-2")).reservationId();
        assertThat(retaken).isPositive();
        Long events = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE event_type = 'ReservationCancelled'")
                .query(Long.class).single();
        assertThat(events).isEqualTo(1L);
    }

    @Test
    void 남의_예매는_취소할_수_없다() {
        long reservationId = holdSeat.hold(new HoldSeatCommand(1L, 54L, "user-1")).reservationId();

        ApiException e = catchThrowableOfType(ApiException.class,
                () -> cancelReservation.cancel(new CancelCommand(reservationId, "user-2")));

        assertThat(e.code()).isEqualTo("RESERVATION_NOT_OWNED");
    }
}
