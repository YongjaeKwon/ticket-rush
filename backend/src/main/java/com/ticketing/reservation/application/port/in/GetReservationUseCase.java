package com.ticketing.reservation.application.port.in;

import com.ticketing.reservation.domain.Reservation;

/** 본인 예매 단건 조회. */
public interface GetReservationUseCase {

    Reservation get(long reservationId, String userId);
}
