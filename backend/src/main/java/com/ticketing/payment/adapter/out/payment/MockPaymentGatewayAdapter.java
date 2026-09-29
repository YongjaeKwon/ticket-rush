package com.ticketing.payment.adapter.out.payment;

import com.ticketing.payment.application.port.out.PaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * Mock PG — 실제 PG의 못된 행동을 설정으로 흉내 낸다.
 * failure-rate = 거절 확률(카드 한도 등, DECLINED — 홀드 유지), error-rate = 시스템 오류 확률
 * (예외 → FAILED — 되돌리기 대상). 이 구분이 결제 결과 이벤트 3종의 입구다.
 * failure-rate 키는 reservation의 동기 어댑터와 공유한다 — 202 전환 때 그쪽 삭제와 함께 정리.
 * 빈 이름 명시: reservation에 같은 이름의 클래스가 있어 기본 빈 이름이 충돌한다(그쪽도 202 전환 때 삭제).
 */
@Component("paymentMockPaymentGateway")
class MockPaymentGatewayAdapter implements PaymentGateway {

    private final long delayMs;
    private final double failureRate;
    private final double errorRate;
    private final Random random = new Random();

    MockPaymentGatewayAdapter(@Value("${payment.mock.delay-ms:0}") long delayMs,
                              @Value("${payment.mock.failure-rate:0.0}") double failureRate,
                              @Value("${payment.mock.error-rate:0.0}") double errorRate) {
        this.delayMs = delayMs;
        this.failureRate = failureRate;
        this.errorRate = errorRate;
    }

    @Override
    public PaymentResult approve(long reservationId, String userId, int amount) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 인터럽트는 고객 거절이 아니라 시스템 사정 — FAILED 경로로 보낸다
                throw new IllegalStateException("interrupted while simulating PG delay", e);
            }
        }
        if (random.nextDouble() < errorRate) {
            throw new IllegalStateException("mock PG error");
        }
        if (random.nextDouble() < failureRate) {
            return PaymentResult.declined();
        }
        return PaymentResult.approvedWith("mock-" + UUID.randomUUID());
    }
}
