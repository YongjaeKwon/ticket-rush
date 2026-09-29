package com.ticketing.payment;

import com.ticketing.payment.application.port.in.ProcessPaymentUseCase;
import com.ticketing.payment.application.port.in.ProcessPaymentUseCase.ProcessPaymentCommand;
import com.ticketing.payment.application.port.out.PaymentGateway;
import com.ticketing.payment.application.port.out.ProcessedEventStore;
import com.ticketing.payment.application.service.ProcessPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 멱등 장부의 진짜 방어선을 실물 MySQL로 검증한다.
 * ① 경쟁: 두 스레드가 선확인을 함께 통과해도(래치로 PG에서 붙잡아 강제) 장부 PK가
 *    한쪽을 밀어내고, 진 쪽은 기록·발행까지 전부 롤백된다.
 * ② 원자성: 장부에 적은 뒤 기록이 실패하면 장부 행도 같이 사라져 재전달이 재처리할 수 있다.
 */
@SpringBootTest(properties = {"outbox.relay.enabled=false", "kafka.consumers.enabled=false"})
@Testcontainers
class PaymentConcurrencyTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    ProcessPaymentUseCase processPayment;
    @Autowired
    ProcessedEventStore processedEvents;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    JdbcClient jdbc;

    /** 두 스레드를 PG 호출 지점에 모아 두었다가 동시에 풀어 경쟁 창을 강제로 연다 */
    @TestConfiguration
    static class LatchedPgConfig {
        static final CountDownLatch bothArrived = new CountDownLatch(2);
        static final CountDownLatch release = new CountDownLatch(1);

        @Bean
        @Primary
        PaymentGateway latchedGateway() {
            return (reservationId, userId, amount) -> {
                bothArrived.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return PaymentGateway.PaymentResult.approvedWith("mock-race");
            };
        }
    }

    @Test
    void 같은_쪽지를_두_스레드가_동시에_처리해도_기록과_발행은_하나다() throws Exception {
        ProcessPaymentCommand command = new ProcessPaymentCommand("race-evt-1", 601L, 1L, "u-race", 50_000);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> processPayment.process(command)),
                    pool.submit(() -> processPayment.process(command)));

            // 둘 다 선확인(alreadyProcessed)을 통과해 PG에서 대기 중 — 경쟁 창이 열렸다
            assertThat(LatchedPgConfig.bothArrived.await(10, TimeUnit.SECONDS)).isTrue();
            LatchedPgConfig.release.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS); // 진 쪽도 예외 없이 조용히 끝난다(로컬 롤백)
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM payment WHERE reservation_id = 601")).isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM outbox WHERE aggregate_type = 'PAYMENT' AND aggregate_id = '601'"))
                .isEqualTo(1L);
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id = 'race-evt-1'")).isEqualTo(1L);
    }

    @Test
    void 기록이_실패하면_장부도_같이_되돌아가_재전달이_재처리할_수_있다() {
        // 실물 장부·트랜잭션에 "저장이 던지는" 가짜 기록소만 끼운 조립
        ProcessPaymentService service = new ProcessPaymentService(
                (r, u, a) -> PaymentGateway.PaymentResult.approvedWith("t"),
                p -> { throw new IllegalStateException("save 실패"); },
                processedEvents,
                e -> { },
                new TransactionTemplate(txManager),
                Clock.systemUTC());

        ProcessPaymentCommand command = new ProcessPaymentCommand("atomic-evt-1", 602L, 1L, "u-atomic", 50_000);
        assertThatThrownBy(() -> service.process(command)).isInstanceOf(IllegalStateException.class);

        // markProcessed는 성공했지만 같은 트랜잭션이라 함께 롤백 — 재전달이 다시 처리할 수 있다
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id = 'atomic-evt-1'")).isEqualTo(0L);
    }

    private Long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
