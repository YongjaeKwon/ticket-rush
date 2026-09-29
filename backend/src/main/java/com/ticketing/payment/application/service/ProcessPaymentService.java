package com.ticketing.payment.application.service;

import com.ticketing.payment.application.port.in.ProcessPaymentUseCase;
import com.ticketing.payment.application.port.out.PaymentEventPublisher;
import com.ticketing.payment.application.port.out.PaymentGateway;
import com.ticketing.payment.application.port.out.PaymentStore;
import com.ticketing.payment.application.port.out.ProcessedEventStore;
import com.ticketing.payment.domain.Payment;
import com.ticketing.payment.domain.PaymentApproved;
import com.ticketing.payment.domain.PaymentDeclined;
import com.ticketing.payment.domain.PaymentFailed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 결제 요청 쪽지 처리 (ADR 0008).
 *
 * 멱등: 장부(processed_event)를 먼저 부작용 없이 확인하고, 기록·발행과 같은 트랜잭션에서
 * 장부에 적는다 — 적기에 실패하면(누가 먼저 적음) 전부 롤백되고 아무 일도 없던 것이 된다.
 * 확인과 기록 사이의 좁은 경쟁 창에서는 PG가 두 번 불릴 수 있지만 기록은 한 번이다
 * (mock 단계에서 감수 — 실 PG는 PG 측 멱등 키로 막는다, backlog).
 * 트랜잭션 단계 실패로 컨테이너가 재전달해도 같다 — 기록이 없으면 사전 확인을 다시 통과해
 * PG부터 전체 재실행된다(기본 에러 핸들러는 백오프 0ms·총 10회). 체크리스트 8에서
 * 백오프·횟수·DLT를 정할 때 "재시도 = PG 재호출"을 전제로 한다.
 *
 * PG 호출은 트랜잭션 밖 — 외부 시스템이 느릴 때 DB 커넥션을 잡지 않는다 (확정 서비스와 같은 원칙).
 * 거절(declined)은 DECLINED 기록 + PaymentDeclined. 예외·타임아웃은 FAILED 기록 + PaymentFailed —
 * 이 구분이 "거절은 홀드 유지, 실패만 되돌리기"의 출발점이다.
 */
@Service
public class ProcessPaymentService implements ProcessPaymentUseCase {

    /** 멱등 장부의 컨슈머 이름 — 이 소비자 기준으로 쪽지 번호가 유일하다 */
    public static final String CONSUMER = "payment.payment-requested";

    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentService.class);

    private final PaymentGateway gateway;
    private final PaymentStore payments;
    private final ProcessedEventStore processedEvents;
    private final PaymentEventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ProcessPaymentService(PaymentGateway gateway, PaymentStore payments,
                                 ProcessedEventStore processedEvents, PaymentEventPublisher events,
                                 TransactionTemplate transaction, Clock clock) {
        this.gateway = gateway;
        this.payments = payments;
        this.processedEvents = processedEvents;
        this.events = events;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public void process(ProcessPaymentCommand command) {
        if (processedEvents.alreadyProcessed(CONSUMER, command.eventId())) {
            log.debug("이미 처리한 결제 요청 — 건너뜀 (eventId={})", command.eventId());
            return;
        }

        Payment payment = attempt(command);

        transaction.executeWithoutResult(status -> {
            if (!processedEvents.markProcessed(CONSUMER, command.eventId())) {
                // 경쟁에서 졌다 — 먼저 적은 쪽의 기록만 남도록 전부 되돌린다
                status.setRollbackOnly();
                return;
            }
            payments.save(payment);
            events.publish(resultEvent(command, payment));
        });
    }

    private Payment attempt(ProcessPaymentCommand c) {
        LocalDateTime now = LocalDateTime.now(clock);
        try {
            PaymentGateway.PaymentResult result = gateway.approve(c.reservationId(), c.userId(), c.amount());
            return result.approved()
                    ? Payment.approved(c.reservationId(), c.amount(), result.transactionId(), now)
                    : Payment.declined(c.reservationId(), c.amount(), now);
        } catch (RuntimeException e) {
            log.warn("PG 호출 실패 — FAILED로 기록 (reservationId={}): {}", c.reservationId(), e.getMessage());
            return Payment.failed(c.reservationId(), c.amount(), now);
        }
    }

    private com.ticketing.shared.event.DomainEvent resultEvent(ProcessPaymentCommand c, Payment p) {
        LocalDateTime at = p.createdAt();
        return switch (p.status()) {
            case APPROVED -> new PaymentApproved(c.reservationId(), c.scheduleId(), c.userId(),
                    c.amount(), p.pgTxId(), at);
            case DECLINED -> new PaymentDeclined(c.reservationId(), c.scheduleId(), c.userId(),
                    c.amount(), at);
            case FAILED -> new PaymentFailed(c.reservationId(), c.scheduleId(), c.userId(),
                    c.amount(), "PG_ERROR", at);
        };
    }
}
