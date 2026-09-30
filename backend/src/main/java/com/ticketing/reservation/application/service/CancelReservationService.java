package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.CancelReservationUseCase;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.application.port.out.SeatHoldStore;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationCancelled;
import com.ticketing.reservation.domain.ReservationException;
import com.ticketing.shared.ApiException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 사용자 취소. HELD에서만 가능 — 확정 후 취소(환불)는 backlog. 결제 중에는 도메인이 막는다.
 * 홀드는 커밋 뒤에만 푼다 — 동시에 겹친 확정 요청이 이겨 롤백됐는데 홀드만 풀리면
 * 결제 중인 좌석이 남에게 열린다(확정·만료·결과 반영과 같은 원칙).
 */
@Service
public class CancelReservationService implements CancelReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final SeatHoldStore seatHoldStore;
    private final EventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public CancelReservationService(ReservationRepository reservationRepository,
                                    SeatHoldStore seatHoldStore, EventPublisher eventPublisher,
                                    TransactionTemplate transaction, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.seatHoldStore = seatHoldStore;
        this.eventPublisher = eventPublisher;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public void cancel(CancelCommand command) {
        Reservation cancelled;
        try {
            cancelled = transaction.execute(status -> {
                Reservation reservation = reservationRepository.findById(command.reservationId())
                        .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND",
                                "예매가 없습니다: " + command.reservationId()));
                if (!reservation.userId().equals(command.userId())) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "RESERVATION_NOT_OWNED",
                            "본인의 예매만 취소할 수 있습니다");
                }
                try {
                    reservation.cancel();
                } catch (ReservationException e) {
                    throw new ApiException(HttpStatus.CONFLICT, e.code(), e.getMessage());
                }
                reservationRepository.save(reservation);
                eventPublisher.publish(ReservationCancelled.from(reservation, LocalDateTime.now(clock)));
                return reservation;
            });
        } catch (OptimisticLockingFailureException e) {
            // 동시에 온 확정 요청·결과 반영·만료가 먼저 바꿨다 — 홀드는 아직 건드리지 않았다
            throw ApiException.conflict("PAYMENT_IN_PROGRESS",
                    "다른 처리가 진행 중입니다. 잠시 후 예매 상태를 확인해 주세요");
        }
        seatHoldStore.release(cancelled.scheduleId(), cancelled.seatId(), cancelled.userId());
    }
}
