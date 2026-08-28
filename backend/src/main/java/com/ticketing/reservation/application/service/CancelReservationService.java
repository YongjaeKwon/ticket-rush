package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.CancelReservationUseCase;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.application.port.out.SeatHoldStore;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationCancelled;
import com.ticketing.reservation.domain.ReservationException;
import com.ticketing.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/** 사용자 취소. HELD에서만 가능 — 확정 후 취소(환불)는 backlog. */
@Service
public class CancelReservationService implements CancelReservationUseCase {

    private final ReservationRepository reservationRepository;
    private final SeatHoldStore seatHoldStore;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    public CancelReservationService(ReservationRepository reservationRepository,
                                    SeatHoldStore seatHoldStore, EventPublisher eventPublisher,
                                    Clock clock) {
        this.reservationRepository = reservationRepository;
        this.seatHoldStore = seatHoldStore;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void cancel(CancelCommand command) {
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
        // 취소 확정과 함께 좌석을 바로 푼다. 롤백 시 키가 먼저 사라지는 극단 케이스는
        // HELD 중복 홀드로 이어질 수 있으나, 최종 방어(confirmed_seat PK)가 이중 확정을 막는다.
        seatHoldStore.release(reservation.scheduleId(), reservation.seatId());
    }
}
