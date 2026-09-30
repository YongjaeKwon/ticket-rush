package com.ticketing.payment.application.port.out;

/** PG 호출 포트 — payment 모듈 소유. 결제를 부르는 곳은 이제 여기뿐이다(3단계 202 전환). */
public interface PaymentGateway {

    PaymentResult approve(long reservationId, String userId, int amount);

    record PaymentResult(boolean approved, String transactionId) {

        public static PaymentResult approvedWith(String transactionId) {
            return new PaymentResult(true, transactionId);
        }

        public static PaymentResult declined() {
            return new PaymentResult(false, null);
        }
    }
}
