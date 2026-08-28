package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.ExpireHoldUseCase;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.application.port.out.SeatHoldStore;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationExpired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 만료 2차 방어. 1차는 Redis TTL(키가 스스로 사라져 좌석이 풀림)이고,
 * 여기는 DB 기준으로 HELD 행을 EXPIRED로 정리한다 — Redis가 유실돼도 DB가 진실이다.
 * Redis 키 삭제는 대부분 이미 사라진 뒤라 방어적 호출이다(없어도 무시).
 */
@Service
public class ExpireHoldService implements ExpireHoldUseCase {

    private final ReservationRepository reservationRepository;
    private final SeatHoldStore seatHoldStore;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    public ExpireHoldService(ReservationRepository reservationRepository, SeatHoldStore seatHoldStore,
                             EventPublisher eventPublisher, Clock clock) {
        this.reservationRepository = reservationRepository;
        this.seatHoldStore = seatHoldStore;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int expireOverdue() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Reservation> overdue = reservationRepository.findExpiredHeld(now);
        for (Reservation reservation : overdue) {
            reservation.expire();
            reservationRepository.save(reservation);
            eventPublisher.publish(ReservationExpired.from(reservation, now));
            seatHoldStore.release(reservation.scheduleId(), reservation.seatId());
        }
        return overdue.size();
    }
}
