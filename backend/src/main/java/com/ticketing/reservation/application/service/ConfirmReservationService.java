package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.domain.PaymentRequested;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationException;
import com.ticketing.shared.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 확정 요청 → 결제 요청 접수 (3단계 비동기 확정, ADR 0008·0009).
 *
 * 한 트랜잭션에서 예매를 "결제 중"으로 바꾸고 PaymentRequested를 서랍(outbox)에 넣는다.
 * PG는 여기서 부르지 않는다 — payment 모듈이 쪽지를 받아 부르고, 결과는 컨슈머
 * (ApplyPaymentResultService)가 예매에 반영한다. 그래서 결제가 느려도 이 요청은 밀리초에 끝난다.
 *
 * 이중 결제 방어는 두 겹이다. 순차로 온 두 번째 요청은 도메인이 PAYMENT_IN_PROGRESS로 막고,
 * 동시에 온 두 요청은 예매의 낙관적 락(version)이 한쪽만 커밋시킨다 — 진 쪽의 쪽지는 함께 롤백된다.
 */
@Service
public class ConfirmReservationService implements ConfirmReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final EventPublisher eventPublisher;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final int ticketPriceKrw;

    public ConfirmReservationService(ReservationRepository reservationRepository,
                                     EventPublisher eventPublisher, TransactionTemplate transaction,
                                     Clock clock, @Value("${ticket.price-krw}") int ticketPriceKrw) {
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
        this.transaction = transaction;
        this.clock = clock;
        this.ticketPriceKrw = ticketPriceKrw;
    }

    @Override
    public ConfirmResult confirm(ConfirmCommand command) {
        try {
            return transaction.execute(status -> {
                Reservation reservation = loadOwned(command.reservationId(), command.userId());
                LocalDateTime now = LocalDateTime.now(clock);
                try {
                    reservation.requestPayment(now);
                } catch (ReservationException e) {
                    throw new ApiException(HttpStatus.CONFLICT, e.code(), e.getMessage());
                }
                Reservation saved = reservationRepository.save(reservation);
                eventPublisher.publish(PaymentRequested.from(saved, ticketPriceKrw, now));
                return new ConfirmResult(saved.id(), saved.status(), saved.paymentStatus());
            });
        } catch (OptimisticLockingFailureException e) {
            // 같은 예매를 다른 요청(동시 확정·취소)이나 결과 반영·만료가 먼저 바꿨다 — 조회로 확인하게 한다
            throw ApiException.conflict("PAYMENT_IN_PROGRESS",
                    "다른 처리가 진행 중입니다. 잠시 후 예매 상태를 확인해 주세요");
        }
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
