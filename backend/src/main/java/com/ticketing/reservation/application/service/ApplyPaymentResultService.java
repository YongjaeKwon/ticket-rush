package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.ProcessedEventStore;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.application.port.out.SeatHoldStore;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationConfirmed;
import com.ticketing.reservation.domain.ReservationException;
import com.ticketing.reservation.domain.ReservationExpired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 결제 결과 쪽지 → 예매 상태 반영. payment 쪽 ProcessPaymentService와 같은 멱등 구조:
 * 장부 선확인(부작용 없음) → 상태 전이·장부 기록·이벤트 발행을 한 트랜잭션 → 경쟁 패배는 전부 롤백.
 *
 * 전이가 불가능한 쪽지(이미 만료·확정, 좌석을 놓침, 예매 없음)는 "기록하고 넘어간다" —
 * 재전달해도 같은 결과라 장부는 남기고 상태는 건드리지 않는다. 특히 승인됐는데
 * confirmed_seat 충돌이면 돈은 나갔는데 좌석이 없는 상황이다: UNIQUE가 이중 예매를 막은
 * 대가이며, 환불 보상은 backlog(실 PG 단계)에서 다룬다.
 *
 * Redis 홀드 해제는 커밋 뒤에 한다 — 롤백됐는데 홀드만 풀리면 좌석을 뺏긴다(확정 서비스와 동일).
 * 커밋과 해제 사이에 죽으면 재전달이 장부 선확인에서 끝나 해제는 재시도되지 않는다 —
 * 키는 남은 TTL(최대 5분)까지 살았다가 스스로 사라진다(1차 방어가 상한). 감수한 창이다.
 *
 * 낙관적 락 충돌(만료 스케줄러·사용자 취소와의 정상 경쟁)은 잡지 않고 전파한다 —
 * 컨테이너가 재전달하면 다음 시도가 바뀐 상태를 읽어 "기록하고 넘어간다"로 수렴한다.
 * 체크리스트 8의 재시도·DLT 설계는 이 분류(락 충돌 = 재시도 가능)를 전제로 한다.
 */
@Service
public class ApplyPaymentResultService implements ApplyPaymentResultUseCase {

    /** 멱등 장부의 컨슈머 이름 */
    public static final String CONSUMER = "reservation.payment-result";

    private static final Logger log = LoggerFactory.getLogger(ApplyPaymentResultService.class);

    private final ReservationRepository reservations;
    private final ProcessedEventStore processedEvents;
    private final SeatHoldStore seatHoldStore;
    private final EventPublisher events;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ApplyPaymentResultService(ReservationRepository reservations,
                                     ProcessedEventStore processedEvents, SeatHoldStore seatHoldStore,
                                     EventPublisher events, TransactionTemplate transaction, Clock clock) {
        this.reservations = reservations;
        this.processedEvents = processedEvents;
        this.seatHoldStore = seatHoldStore;
        this.events = events;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public void apply(PaymentResultCommand command) {
        if (processedEvents.alreadyProcessed(CONSUMER, command.eventId())) {
            log.debug("이미 반영한 결제 결과 — 건너뜀 (eventId={})", command.eventId());
            return;
        }

        Reservation changed;
        try {
            changed = transaction.execute(status -> {
                if (!processedEvents.markProcessed(CONSUMER, command.eventId())) {
                    status.setRollbackOnly(); // 경쟁에서 졌다 — 먼저 적은 쪽만 남긴다
                    return null;
                }
                return switch (command.result()) {
                    case APPROVED -> applyApproved(command.reservationId());
                    case FAILED -> applyFailed(command.reservationId());
                    case DECLINED -> {
                        // 거절은 전이가 아니다 — HELD 유지, 남은 시간 안에 재시도 가능 (2-2)
                        log.info("결제 거절 — HELD 유지 (reservationId={})", command.reservationId());
                        yield null;
                    }
                };
            });
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // 만료 스케줄러·취소와의 정상 경쟁 — 전부 롤백됐고, 재전달의 다음 시도가 수렴시킨다
            log.warn("결제 결과 반영 중 버전 충돌 — 재전달로 수렴 (reservationId={})", command.reservationId());
            throw e;
        }

        if (changed != null) {
            seatHoldStore.release(changed.scheduleId(), changed.seatId(), changed.userId());
        }
    }

    /** 확정. 순서 주의: 좌석 기록(UNIQUE)을 먼저 — 실패해도 상태 저장 전이라 되돌릴 게 없다. */
    private Reservation applyApproved(long reservationId) {
        Reservation reservation = reservations.findById(reservationId).orElse(null);
        if (reservation == null) {
            log.error("승인 쪽지의 예매가 없다 — 장부만 남김 (reservationId={})", reservationId);
            return null;
        }
        try {
            reservation.confirm(LocalDateTime.now(clock)); // 아직 저장 전 — 실패하면 그대로 버려진다
        } catch (ReservationException e) {
            log.warn("승인 쪽지를 반영할 수 없다({}) — 상태 유지, 장부만 남김 (reservationId={})",
                    e.code(), reservationId);
            return null;
        }
        if (!reservations.registerConfirmedSeat(
                reservation.scheduleId(), reservation.seatId(), reservation.id())) {
            // 이중 예매를 UNIQUE가 막았다 — 돈은 승인됐는데 좌석을 놓친 경우. 환불 보상은 backlog
            log.error("승인됐지만 좌석이 이미 확정돼 있다 — 확정하지 않음, 환불 보상 필요 (reservationId={})",
                    reservationId);
            return null;
        }
        Reservation saved = reservations.save(reservation);
        events.publish(ReservationConfirmed.from(saved, LocalDateTime.now(clock)));
        return saved;
    }

    /** 되돌리기 — 결제가 시스템 사정으로 실패했으니 좌석을 풀어준다(EXPIRED). */
    private Reservation applyFailed(long reservationId) {
        Reservation reservation = reservations.findById(reservationId).orElse(null);
        if (reservation == null) {
            log.error("실패 쪽지의 예매가 없다 — 장부만 남김 (reservationId={})", reservationId);
            return null;
        }
        try {
            reservation.expire();
        } catch (ReservationException e) {
            log.warn("실패 쪽지를 반영할 수 없다({}) — 상태 유지, 장부만 남김 (reservationId={})",
                    e.code(), reservationId);
            return null;
        }
        Reservation saved = reservations.save(reservation);
        events.publish(ReservationExpired.from(saved, LocalDateTime.now(clock)));
        return saved;
    }
}
