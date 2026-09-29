package com.ticketing.payment.application.service;

import com.ticketing.payment.application.port.in.ProcessPaymentUseCase.ProcessPaymentCommand;
import com.ticketing.payment.application.port.out.PaymentEventPublisher;
import com.ticketing.payment.application.port.out.PaymentGateway;
import com.ticketing.payment.application.port.out.PaymentStore;
import com.ticketing.payment.application.port.out.ProcessedEventStore;
import com.ticketing.payment.domain.Payment;
import com.ticketing.shared.event.DomainEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 처리의 세 갈래(승인·거절·실패)와 멱등을 — 스프링 없이 가짜 포트로 검증한다.
 * 진짜 트랜잭션(롤백·장부 원자성)은 PaymentConcurrencyTest가 실물 MySQL로 검증한다.
 */
class ProcessPaymentServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-11T05:00:00Z"), ZoneOffset.UTC);
    private static final ProcessPaymentCommand COMMAND =
            new ProcessPaymentCommand("evt-1", 77L, 1L, "u-1", 134_000);

    private final List<Payment> saved = new ArrayList<>();
    private final List<DomainEvent> published = new ArrayList<>();
    private final Set<String> ledger = new HashSet<>();
    private final AtomicInteger pgCalls = new AtomicInteger();

    private ProcessPaymentService service(PaymentGateway gateway) {
        ProcessedEventStore processed = new ProcessedEventStore() {
            @Override public boolean alreadyProcessed(String consumer, String eventId) {
                return ledger.contains(consumer + ":" + eventId);
            }
            @Override public boolean markProcessed(String consumer, String eventId) {
                return ledger.add(consumer + ":" + eventId);
            }
        };
        return service(gateway, processed);
    }

    private ProcessPaymentService service(PaymentGateway gateway, ProcessedEventStore processed) {
        PaymentStore store = saved::add;
        PaymentEventPublisher publisher = published::add;
        PaymentGateway counting = (r, u, a) -> {
            pgCalls.incrementAndGet();
            return gateway.approve(r, u, a);
        };
        // 콜백만 실행하는 가짜 — 트랜잭션·롤백 의미는 여기서 검증하지 않는다(통합 테스트 몫)
        TransactionTemplate tx = new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        return new ProcessPaymentService(counting, store, processed, publisher, tx, FIXED);
    }

    @Test
    void 승인이면_APPROVED_기록과_PaymentApproved_발행() {
        service((r, u, a) -> PaymentGateway.PaymentResult.approvedWith("mock-tx-1")).process(COMMAND);

        assertThat(saved).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(Payment.Status.APPROVED);
            assertThat(p.pgTxId()).isEqualTo("mock-tx-1");
        });
        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("PaymentApproved");
            assertThat(e.payload().get("scheduleId")).isEqualTo(1L); // 파티션 키 규약
        });
    }

    @Test
    void 거절이면_DECLINED_기록과_PaymentDeclined_발행_pgTxId는_없다() {
        service((r, u, a) -> PaymentGateway.PaymentResult.declined()).process(COMMAND);

        assertThat(saved).singleElement().satisfies(p -> {
            assertThat(p.status()).isEqualTo(Payment.Status.DECLINED);
            assertThat(p.pgTxId()).isNull();
        });
        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("PaymentDeclined");
            assertThat(e.payload().get("scheduleId")).isEqualTo(1L); // 파티션 키 규약
        });
    }

    @Test
    void PG가_예외를_던지면_FAILED_기록과_PaymentFailed_발행() {
        service((r, u, a) -> { throw new IllegalStateException("PG 응답 없음"); }).process(COMMAND);

        assertThat(saved).singleElement()
                .satisfies(p -> assertThat(p.status()).isEqualTo(Payment.Status.FAILED));
        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("PaymentFailed");
            assertThat(e.payload().get("reason")).isEqualTo("PG_ERROR");
            assertThat(e.payload().get("scheduleId")).isEqualTo(1L); // 파티션 키 규약
        });
    }

    @Test
    void 같은_쪽지_번호는_두_번_처리되지_않는다() {
        ProcessPaymentService service = service((r, u, a) -> PaymentGateway.PaymentResult.approvedWith("t"));
        service.process(COMMAND);
        service.process(COMMAND);

        // 선확인이 PG 재호출까지 막는다 — attempt가 선확인 앞으로 가는 회귀를 잡는 단언
        assertThat(pgCalls).hasValue(1);
        assertThat(saved).hasSize(1);
        assertThat(published).hasSize(1);
    }

    @Test
    void 확인과_기록_사이_경쟁에서_지면_기록도_발행도_없다() {
        // 선확인은 통과했는데 장부에 적을 때 누가 먼저 적은 상황 — markProcessed만 false
        ProcessedEventStore raced = new ProcessedEventStore() {
            @Override public boolean alreadyProcessed(String consumer, String eventId) { return false; }
            @Override public boolean markProcessed(String consumer, String eventId) { return false; }
        };
        service((r, u, a) -> PaymentGateway.PaymentResult.approvedWith("t"), raced).process(COMMAND);

        assertThat(saved).isEmpty();
        assertThat(published).isEmpty();
    }
}
