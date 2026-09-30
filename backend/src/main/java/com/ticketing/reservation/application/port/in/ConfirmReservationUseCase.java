package com.ticketing.reservation.application.port.in;

import com.ticketing.reservation.domain.PaymentStatus;
import com.ticketing.reservation.domain.ReservationStatus;

/**
 * 확정 요청 — 결제를 요청(PaymentRequested)하고 곧바로 접수 결과를 돌려준다 (ADR 0009).
 * 판정(승인·거절·실패)은 결제 결과 쪽지가 도착한 뒤 조회로 확인한다.
 */
public interface ConfirmReservationUseCase {

    ConfirmResult confirm(ConfirmCommand command);

    record ConfirmCommand(long reservationId, String userId) {
    }

    /** 접수 결과 — 판정이 아니다. 보통 HELD + REQUESTED */
    record ConfirmResult(long reservationId, ReservationStatus status, PaymentStatus paymentStatus) {
    }
}
