package com.ticketing.reservation.application.port.in;

/** 사용자가 홀드 중인 예매를 취소한다. 확정 후 취소(환불)는 범위 밖. */
public interface CancelReservationUseCase {

    void cancel(CancelCommand command);

    record CancelCommand(long reservationId, String userId) {
    }
}
