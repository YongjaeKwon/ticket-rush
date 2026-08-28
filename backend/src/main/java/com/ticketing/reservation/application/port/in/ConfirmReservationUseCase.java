package com.ticketing.reservation.application.port.in;

import com.ticketing.reservation.domain.ReservationStatus;

/** 결제 승인 후 예매를 확정한다. 같은 좌석의 두 번째 확정은 SEAT_ALREADY_CONFIRMED. */
public interface ConfirmReservationUseCase {

    ConfirmResult confirm(ConfirmCommand command);

    record ConfirmCommand(long reservationId, String userId) {
    }

    record ConfirmResult(long reservationId, ReservationStatus status, String paymentTransactionId) {
    }
}
