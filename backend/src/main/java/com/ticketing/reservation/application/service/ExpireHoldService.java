package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.ExpireHoldUseCase;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.application.port.out.SeatHoldStore;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationExpired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 만료 2차 방어. 1차는 Redis TTL(키가 스스로 사라져 좌석이 풀림)이고,
 * 여기는 DB 기준으로 HELD 행을 EXPIRED로 정리한다 — Redis가 유실돼도 DB가 진실이다.
 * Redis 키 삭제는 대부분 이미 사라진 뒤라 방어적 호출이다(없어도 무시).
 *
 * 트랜잭션은 건별로 연다 — 한 배치(100건)를 한 트랜잭션으로 묶으면, 결제 결과 컨슈머가
 * 같은 예매를 먼저 처리했을 때(낙관적 락 충돌) 충돌 1건이 나머지 99건까지 롤백시킨다.
 * 충돌한 행은 남이 이미 정리한 것이니 건너뛰면 된다. 해제는 그 행의 커밋 뒤에.
 */
@Service
public class ExpireHoldService implements ExpireHoldUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpireHoldService.class);

    private final ReservationRepository reservationRepository;
    private final SeatHoldStore seatHoldStore;
    private final EventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ExpireHoldService(ReservationRepository reservationRepository, SeatHoldStore seatHoldStore,
                             EventPublisher eventPublisher, TransactionTemplate transaction, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.seatHoldStore = seatHoldStore;
        this.eventPublisher = eventPublisher;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public int expireOverdue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Reservation> overdue = reservationRepository.findExpiredHeld(now);
        int expired = 0;
        for (Reservation reservation : overdue) {
            try {
                transaction.executeWithoutResult(status -> {
                    reservation.expire();
                    reservationRepository.save(reservation);
                    eventPublisher.publish(ReservationExpired.from(reservation, now));
                });
            } catch (OptimisticLockingFailureException e) {
                // 결제 결과 컨슈머·확정이 먼저 처리한 행 — 이번 틱은 이 행만 건너뛴다
                log.debug("만료 건너뜀 — 다른 처리가 선점 (reservationId={})", reservation.id());
                continue;
            }
            seatHoldStore.release(reservation.scheduleId(), reservation.seatId(), reservation.userId());
            expired++;
        }
        return expired;
    }
}
