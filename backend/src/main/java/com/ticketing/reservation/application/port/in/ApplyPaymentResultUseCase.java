package com.ticketing.reservation.application.port.in;

/**
 * 결제 결과 쪽지를 예매 상태에 반영한다 (3단계, payment.events 컨슈머의 입구).
 * APPROVED → 확정 / FAILED → 홀드 해제(EXPIRED, 되돌리기) / DECLINED → HELD 유지(재시도 가능).
 */
public interface ApplyPaymentResultUseCase {

    void apply(PaymentResultCommand command);

    enum PaymentResult { APPROVED, DECLINED, FAILED }

    /** eventId는 멱등 장부의 키 — 같은 쪽지가 두 번 와도 한 번만 반영된다. */
    record PaymentResultCommand(String eventId, long reservationId, PaymentResult result) {
    }
}
