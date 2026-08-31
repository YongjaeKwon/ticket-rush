package com.ticketing.reservation;

import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase.ConfirmCommand;
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

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이 프로젝트의 핵심 증명 (체크리스트 9번).
 * "수천 명이 같은 좌석을 동시에 잡아도 이중 예매 0건" — 그 축소판을 실물 MySQL·Redis로 재현한다.
 * CountDownLatch로 모든 스레드를 출발선에 세워뒀다가 동시에 발사한다.
 */
@SpringBootTest(properties = "hold-expiry.enabled=false")
@Testcontainers
class ReservationConcurrencyTest {

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

    /** 각 스레드의 결과를 모은다 — 성공 수, 실패 코드들. */
    private record RaceResult(AtomicInteger success, Queue<String> failureCodes) {

        static RaceResult empty() {
            return new RaceResult(new AtomicInteger(), new ConcurrentLinkedQueue<>());
        }
    }

    /** 러너 n개를 출발선에 세우고 동시에 출발시킨 뒤 전원 완주까지 기다린다. */
    private RaceResult race(int runners, java.util.function.IntConsumer task) throws InterruptedException {
        RaceResult result = RaceResult.empty();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(runners);
        try (ExecutorService pool = Executors.newFixedThreadPool(runners)) {
            for (int i = 0; i < runners; i++) {
                int runner = i;
                pool.submit(() -> {
                    try {
                        start.await();                       // 출발 신호까지 대기
                        task.accept(runner);
                        result.success().incrementAndGet();
                    } catch (ApiException e) {
                        result.failureCodes().add(e.code());
                    } catch (Exception e) {
                        result.failureCodes().add(e.getClass().getSimpleName());
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();                               // 동시 출발
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        }
        return result;
    }

    @Test
    void 한_좌석에_100명이_동시에_달려들면_성공은_정확히_1명이다() throws InterruptedException {
        long seatId = 100L;

        long startedAt = System.currentTimeMillis();
        RaceResult result = race(100, runner ->
                holdSeat.hold(new HoldSeatCommand(1L, seatId, "user-" + runner)));
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result.success().get()).isEqualTo(1);
        assertThat(result.failureCodes()).hasSize(99);
        assertThat(result.failureCodes()).containsOnly("SEAT_ALREADY_HELD");

        // DB에도 예매(HELD)는 정확히 한 건
        Long held = jdbc.sql("SELECT COUNT(*) FROM reservation WHERE seat_id = :seat")
                .param("seat", seatId).query(Long.class).single();
        assertThat(held).isEqualTo(1L);
        // Outbox 이벤트도 성공한 한 건만큼만 쌓였다
        Long events = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE event_type = 'ReservationHeld'")
                .query(Long.class).single();
        assertThat(events).isEqualTo(1L);

        System.out.printf("[동시성] 1석 100요청: 성공 1, SEAT_ALREADY_HELD 99, %dms%n", elapsed);
    }

    @Test
    void 홀드가_중복된_비상_상황에서도_동시_확정의_승자는_1명이다() throws InterruptedException {
        // Redis가 죽어 홀드 키가 사라진 상황을 재현 — 같은 좌석에 HELD 예매 10건을 만든다
        long seatId = 101L;
        List<Long> reservationIds = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            reservationIds.add(holdSeat.hold(
                    new HoldSeatCommand(1L, seatId, "user-" + i)).reservationId());
            redisTemplate.delete("hold:1:" + seatId);        // 홀드 유실 재현
        }

        RaceResult result = race(10, runner ->
                confirmReservation.confirm(new ConfirmCommand(reservationIds.get(runner), "user-" + runner)));

        assertThat(result.success().get()).isEqualTo(1);
        assertThat(result.failureCodes()).hasSize(9);
        assertThat(result.failureCodes()).containsOnly("SEAT_ALREADY_CONFIRMED");

        // 최종 방어선: 확정 좌석은 정확히 1행 — 이중 예매 0건
        Long confirmed = jdbc.sql("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = :seat")
                .param("seat", seatId).query(Long.class).single();
        assertThat(confirmed).isEqualTo(1L);
        Long confirmedReservations = jdbc.sql(
                        "SELECT COUNT(*) FROM reservation WHERE seat_id = :seat AND status = 'CONFIRMED'")
                .param("seat", seatId).query(Long.class).single();
        assertThat(confirmedReservations).isEqualTo(1L);

        System.out.printf("[동시성] 중복 홀드 10건 동시 확정: 성공 1, SEAT_ALREADY_CONFIRMED 9%n");
    }

    @Test
    void 서로_다른_좌석_100건은_전부_성공한다() throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        RaceResult result = race(100, runner ->
                holdSeat.hold(new HoldSeatCommand(1L, 200L + runner, "user-" + runner)));
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result.success().get()).isEqualTo(100);
        assertThat(result.failureCodes()).isEmpty();

        System.out.printf("[동시성] 100석 100요청: 전부 성공, %dms%n", elapsed);
    }
}
