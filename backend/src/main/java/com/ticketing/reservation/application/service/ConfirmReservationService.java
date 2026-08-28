package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.PaymentGateway;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.application.port.out.SeatHoldStore;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationConfirmed;
import com.ticketing.reservation.domain.ReservationException;
import com.ticketing.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 결제 승인 → 확정 (ARCHITECTURE 4-3 "확정 시" 순서).
 *
 * 트랜잭션 경계에 주의: PG 호출은 외부 시스템이라 트랜잭션 "밖"에서 하고,
 * DB 작업(확정 전이 + confirmed_seat 기록 + Outbox)만 TransactionTemplate로 묶는다.
 * PG를 트랜잭션 안에서 부르면 결제가 느릴 때 DB 커넥션을 그만큼 붙잡는다.
 *
 * Redis 홀드 해제는 커밋이 끝난 다음에 한다 — 확정이 롤백됐는데
 * 홀드만 풀리면 다른 사용자가 좌석을 채 갈 수 있기 때문이다.
 */
@Service
public class ConfirmReservationService implements ConfirmReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final PaymentGateway paymentGateway;
    private final SeatHoldStore seatHoldStore;
    private final EventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public ConfirmReservationService(ReservationRepository reservationRepository,
                                     PaymentGateway paymentGateway, SeatHoldStore seatHoldStore,
                                     EventPublisher eventPublisher, TransactionTemplate transaction,
                                     Clock clock) {
        this.reservationRepository = reservationRepository;
        this.paymentGateway = paymentGateway;
        this.seatHoldStore = seatHoldStore;
        this.eventPublisher = eventPublisher;
        this.transaction = transaction;
        this.clock = clock;
    }

    @Override
    public ConfirmResult confirm(ConfirmCommand command) {
        Reservation reservation = loadOwned(command.reservationId(), command.userId());

        // 결제(돈)가 나가기 전의 선검사 — 최종 판정은 트랜잭션 안의 도메인 confirm()이 다시 한다
        if (!reservation.isHeld()) {
            throw ApiException.conflict("INVALID_RESERVATION_STATE",
                    reservation.status() + " 상태의 예매는 결제할 수 없습니다");
        }
        if (LocalDateTime.now(clock).isAfter(reservation.expiresAt())) {
            throw ApiException.conflict("HOLD_EXPIRED", "홀드가 만료됐습니다. 좌석을 다시 선택하세요");
        }

        PaymentGateway.PaymentResult payment =
                paymentGateway.approve(reservation.id(), reservation.userId());
        if (!payment.approved()) {
            // 거절은 전이가 아니다 — HELD 유지, 남은 시간 안에 재시도 가능
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED",
                    "결제가 거절됐습니다. 홀드는 유지 중입니다");
        }

        Reservation confirmed = transaction.execute(status -> {
            Reservation fresh = loadOwned(command.reservationId(), command.userId());
            try {
                fresh.confirm(LocalDateTime.now(clock));
            } catch (ReservationException e) {
                throw new ApiException(HttpStatus.CONFLICT, e.code(), e.getMessage());
            }
            Reservation saved = reservationRepository.save(fresh);
            if (!reservationRepository.registerConfirmedSeat(
                    saved.scheduleId(), saved.seatId(), saved.id())) {
                throw ApiException.conflict("SEAT_ALREADY_CONFIRMED",
                        "이미 확정된 좌석입니다");   // 예외 → 롤백 → HELD로 남는다
            }
            eventPublisher.publish(ReservationConfirmed.from(saved, LocalDateTime.now(clock)));
            return saved;
        });

        seatHoldStore.release(confirmed.scheduleId(), confirmed.seatId());
        return new ConfirmResult(confirmed.id(), confirmed.status(), payment.transactionId());
    }

    private Reservation loadOwned(long reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND",
                        "예매가 없습니다: " + reservationId));
        if (!reservation.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RESERVATION_NOT_OWNED",
                    "본인의 예매만 처리할 수 있습니다");
        }
        return reservation;
    }
}
