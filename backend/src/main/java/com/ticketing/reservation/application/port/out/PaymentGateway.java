package com.ticketing.reservation.application.port.out;

/**
 * 결제 승인. 1단계 구현은 Mock(지연·실패율 설정 가능) 동기 호출,
 * 3단계에서 이벤트 기반으로 교체 — 이 포트 덕에 도메인·서비스는 그대로다.
 * 금액은 1단계 범위 밖(스키마에 가격이 없다) — 3단계 payment 분리 때 추가.
 */
public interface PaymentGateway {

    PaymentResult approve(long reservationId, String userId);

    /** declined면 approved=false — 예외가 아니라 정상 응답이다 (HELD 유지, 재시도 가능). */
    record PaymentResult(boolean approved, String transactionId) {

        public static PaymentResult approvedWith(String transactionId) {
            return new PaymentResult(true, transactionId);
        }

        public static PaymentResult declined() {
            return new PaymentResult(false, null);
        }
    }
}
