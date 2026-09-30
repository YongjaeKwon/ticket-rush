package com.ticketing.reservation;

import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase;
import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResult;
import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResultCommand;
import com.ticketing.reservation.application.port.in.CancelReservationUseCase.CancelCommand;
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
@SpringBootTest(properties = {"hold-expiry.enabled=false", "outbox.relay.enabled=false", "kafka.consumers.enabled=false"})
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
    com.ticketing.queue.application.port.out.AdmissionTokenIssuer tokenIssuer;

    private String admissionFor(String userId) {
        return tokenIssuer.issue(1L, userId);
    }

    @Autowired
    ConfirmReservationUseCase confirmReservation;

    @Autowired
    ApplyPaymentResultUseCase applyPaymentResult;

    @Autowired
    com.ticketing.reservation.application.port.in.CancelReservationUseCase cancelReservation;

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
                holdSeat.hold(new HoldSeatCommand(1L, seatId, "user-" + runner, admissionFor("user-" + runner))));
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
    void 홀드가_중복된_비상_상황에서도_동시_승인_반영의_승자는_1명이다() throws InterruptedException {
        // Redis가 죽어 홀드 키가 사라진 상황을 재현 — 같은 좌석에 결제 요청까지 마친 HELD 예매 10건
        long seatId = 101L;
        List<Long> reservationIds = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            long id = holdSeat.hold(
                    new HoldSeatCommand(1L, seatId, "user-" + i, admissionFor("user-" + i))).reservationId();
            redisTemplate.delete("hold:1:" + seatId);        // 홀드 유실 재현
            confirmReservation.confirm(new ConfirmCommand(id, "user-" + i));
            reservationIds.add(id);
        }

        // 결제가 10건 모두 승인됐다 — 결과 쪽지 10장이 동시에 반영된다(비동기 확정의 경합 지점)
        RaceResult result = race(10, runner -> applyPaymentResult.apply(new PaymentResultCommand(
                "approve-" + runner, reservationIds.get(runner), PaymentResult.APPROVED, "mock-tx-" + runner)));

        // 진 쪽은 예외가 아니라 "기록하고 넘어간다" — 좌석을 놓친 승인은 로그와 장부로 남는다(환불은 backlog)
        assertThat(result.failureCodes()).isEmpty();

        // 최종 방어선: 확정 좌석은 정확히 1행 — 이중 예매 0건
        Long confirmed = jdbc.sql("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = :seat")
                .param("seat", seatId).query(Long.class).single();
        assertThat(confirmed).isEqualTo(1L);
        Long confirmedReservations = jdbc.sql(
                        "SELECT COUNT(*) FROM reservation WHERE seat_id = :seat AND status = 'CONFIRMED'")
                .param("seat", seatId).query(Long.class).single();
        assertThat(confirmedReservations).isEqualTo(1L);
        // 좌석을 놓친 9건은 결론(EXPIRED + APPROVED)으로 닫혀 조회에 드러난다 — 결제 중으로 묶이지 않는다
        Long lost = jdbc.sql("SELECT COUNT(*) FROM reservation WHERE seat_id = :seat"
                        + " AND status = 'EXPIRED' AND payment_status = 'APPROVED'")
                .param("seat", seatId).query(Long.class).single();
        assertThat(lost).isEqualTo(9L);

        System.out.printf("[동시성] 중복 홀드 10건 동시 승인 반영: 확정 1, 좌석을 놓친 9건 EXPIRED+APPROVED%n");
    }

    @Test
    void 취소와_확정이_동시에_와도_결제_중인_좌석의_홀드는_풀리지_않는다() throws InterruptedException {
        // 취소가 커밋 전에 홀드를 풀면, 확정이 이겨 결제가 진행 중인 좌석이 남에게 열린다
        for (int round = 0; round < 10; round++) {
            long seatId = 200L + round;
            String userId = "racer-" + round;
            long id = holdSeat.hold(new HoldSeatCommand(1L, seatId, userId, admissionFor(userId))).reservationId();

            RaceResult result = race(2, runner -> {
                if (runner == 0) {
                    confirmReservation.confirm(new ConfirmCommand(id, userId));
                } else {
                    cancelReservation.cancel(new CancelCommand(id, userId));
                }
            });

            // 진 쪽은 409(PAYMENT_IN_PROGRESS·INVALID_RESERVATION_STATE)로 끝난다 — 500이 없다
            assertThat(result.failureCodes()).allSatisfy(code -> assertThat(code)
                    .isIn("PAYMENT_IN_PROGRESS", "INVALID_RESERVATION_STATE"));
            String state = jdbc.sql("SELECT CONCAT(status, '|', COALESCE(payment_status, '-')) FROM reservation WHERE id = " + id)
                    .query(String.class).single();
            if (state.equals("HELD|REQUESTED")) {
                assertThat(redisTemplate.hasKey("hold:1:" + seatId))
                        .as("결제 중인 좌석의 홀드는 살아 있어야 한다 (round %d)", round).isTrue();
            } else {
                assertThat(state).isEqualTo("CANCELLED|-");
            }
        }
    }

    @Test
    void 같은_예매에_확정_요청이_동시에_몰려도_결제_요청_쪽지는_한_장이다() throws InterruptedException {
        long id = holdSeat.hold(new HoldSeatCommand(1L, 102L, "user-1", admissionFor("user-1"))).reservationId();

        // 여러 탭·새 접수번호로 동시에 "결제하기" — 쪽지가 두 장 나가면 돈이 두 번 승인된다
        RaceResult result = race(10, runner -> confirmReservation.confirm(new ConfirmCommand(id, "user-1")));

        assertThat(result.success().get()).isEqualTo(1);
        // 순차로 늦은 쪽은 도메인이, 동시에 겹친 쪽은 낙관적 락이 막는다 — 둘 다 같은 코드로 보인다
        assertThat(result.failureCodes()).hasSize(9).containsOnly("PAYMENT_IN_PROGRESS");
        Long requests = jdbc.sql("SELECT COUNT(*) FROM outbox WHERE event_type = 'PaymentRequested'")
                .query(Long.class).single();
        assertThat(requests).isEqualTo(1L);

        System.out.printf("[동시성] 한 예매에 확정 요청 10건 동시: 접수 1, PAYMENT_IN_PROGRESS 9%n");
    }

    @Test
    void 서로_다른_좌석_100건은_전부_성공한다() throws InterruptedException {
        long startedAt = System.currentTimeMillis();
        RaceResult result = race(100, runner ->
                holdSeat.hold(new HoldSeatCommand(1L, 200L + runner, "user-" + runner, admissionFor("user-" + runner))));
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result.success().get()).isEqualTo(100);
        assertThat(result.failureCodes()).isEmpty();

        System.out.printf("[동시성] 100석 100요청: 전부 성공, %dms%n", elapsed);
    }
}
