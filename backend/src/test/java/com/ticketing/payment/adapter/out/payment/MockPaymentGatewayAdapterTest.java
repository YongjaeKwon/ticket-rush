package com.ticketing.payment.adapter.out.payment;

import com.ticketing.payment.application.port.out.PaymentGateway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 거절(DECLINED 반환)과 시스템 오류(예외)가 다른 출구로 나가는지 — 결과 이벤트 3종의 입구 검증. */
class MockPaymentGatewayAdapterTest {

    @Test
    void error_rate가_걸리면_예외를_던진다_FAILED_경로() {
        PaymentGateway pg = new MockPaymentGatewayAdapter(0, 0.0, 1.0);

        assertThatThrownBy(() -> pg.approve(1L, "u-1", 1000))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failure_rate가_걸리면_거절을_반환한다_DECLINED_경로() {
        PaymentGateway pg = new MockPaymentGatewayAdapter(0, 1.0, 0.0);

        assertThat(pg.approve(1L, "u-1", 1000).approved()).isFalse();
    }

    @Test
    void 둘_다_0이면_승인한다() {
        PaymentGateway pg = new MockPaymentGatewayAdapter(0, 0.0, 0.0);

        PaymentGateway.PaymentResult result = pg.approve(1L, "u-1", 1000);
        assertThat(result.approved()).isTrue();
        assertThat(result.transactionId()).startsWith("mock-");
    }
}
