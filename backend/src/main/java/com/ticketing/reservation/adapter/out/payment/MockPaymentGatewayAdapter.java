package com.ticketing.reservation.adapter.out.payment;

import com.ticketing.reservation.application.port.out.PaymentGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * Mock PG. 실제 PG의 못된 행동(느림, 거절)을 설정으로 흉내 낸다 —
 * payment.mock.delay-ms(지연), payment.mock.failure-rate(0.0~1.0 거절 확률).
 * 3단계에서 이벤트 기반 결제로, 그 이후엔 실 PG 어댑터로 교체할 수 있는 자리다.
 */
@Component
class MockPaymentGatewayAdapter implements PaymentGateway {

    private final long delayMs;
    private final double failureRate;
    private final Random random = new Random();

    MockPaymentGatewayAdapter(@Value("${payment.mock.delay-ms:0}") long delayMs,
                              @Value("${payment.mock.failure-rate:0.0}") double failureRate) {
        this.delayMs = delayMs;
        this.failureRate = failureRate;
    }

    @Override
    public PaymentResult approve(long reservationId, String userId) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return PaymentResult.declined();
            }
        }
        if (random.nextDouble() < failureRate) {
            return PaymentResult.declined();
        }
        return PaymentResult.approvedWith("mock-" + UUID.randomUUID());
    }
}
