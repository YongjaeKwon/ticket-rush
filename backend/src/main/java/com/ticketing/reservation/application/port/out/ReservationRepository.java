package com.ticketing.reservation.application.port.out;

import com.ticketing.reservation.domain.Reservation;

import java.util.Optional;

/** 예매 영속화. 구현은 JPA 어댑터 — 엔티티↔도메인 변환은 어댑터의 일이다. */
public interface ReservationRepository {

    /** 저장 후 id·version이 채워진 도메인 객체를 돌려준다. */
    Reservation save(Reservation reservation);

    Optional<Reservation> findById(long reservationId);
}
