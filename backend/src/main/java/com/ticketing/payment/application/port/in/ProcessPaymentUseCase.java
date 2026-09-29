package com.ticketing.payment.application.port.in;

/** 결제 요청 쪽지(PaymentRequested) 하나를 처리한다 — 같은 쪽지가 두 번 와도 한 번만. */
public interface ProcessPaymentUseCase {

    void process(ProcessPaymentCommand command);

    /** eventId = 봉투의 쪽지 번호. 멱등 장부(processed_event)의 열쇠다 */
    record ProcessPaymentCommand(
            String eventId,
            long reservationId,
            long scheduleId,
            String userId,
            int amount
    ) {
    }
}
