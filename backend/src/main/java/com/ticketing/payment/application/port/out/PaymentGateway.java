package com.ticketing.payment.application.port.out;

/**
 * PG 호출 포트 — payment 모듈 소유.
 * (reservation에도 같은 이름의 포트가 남아 있다: 동기 확정 경로가 202 전환 단위에서
 *  이벤트로 바뀌면 그쪽은 삭제된다. 잠깐의 중복이 모듈 경계보다 싸다)
 */
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
