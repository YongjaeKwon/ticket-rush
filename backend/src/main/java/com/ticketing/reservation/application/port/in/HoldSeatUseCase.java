package com.ticketing.reservation.application.port.in;

import java.time.LocalDateTime;

/** 좌석 하나를 5분간 선점한다. 이미 선점된 좌석이면 SEAT_ALREADY_HELD. */
public interface HoldSeatUseCase {

    HoldResult hold(HoldSeatCommand command);

    record HoldSeatCommand(long scheduleId, long seatId, String userId) {
    }

    record HoldResult(long reservationId, LocalDateTime expiresAt) {
    }
}
