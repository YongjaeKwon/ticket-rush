package com.ticketing.reservation.adapter.in.messaging;

import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResult;
import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResultCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 문지기 검증 — 결제 결과 3종만 통과하고, 깨진 쪽지·남의 쪽지는 조용히 넘긴다. */
class PaymentResultListenerTest {

    private final List<PaymentResultCommand> captured = new ArrayList<>();
    private final PaymentResultListener listener = new PaymentResultListener(captured::add);

    private static String envelope(String eventType, String payload) {
        return """
                {"eventId":"evt-1","eventType":"%s","version":1,\
                "occurredAt":"2026-09-29T05:00:00","aggregateId":"501","payload":%s}"""
                .formatted(eventType, payload);
    }

    @Test
    void 결제_결과_3종이_각자의_커맨드로_추출된다() {
        listener.onMessage(envelope("PaymentApproved", "{\"reservationId\":501,\"scheduleId\":1}"));
        listener.onMessage(envelope("PaymentDeclined", "{\"reservationId\":502,\"scheduleId\":1}"));
        listener.onMessage(envelope("PaymentFailed", "{\"reservationId\":503,\"scheduleId\":1}"));

        assertThat(captured).containsExactly(
                new PaymentResultCommand("evt-1", 501L, PaymentResult.APPROVED),
                new PaymentResultCommand("evt-1", 502L, PaymentResult.DECLINED),
                new PaymentResultCommand("evt-1", 503L, PaymentResult.FAILED));
    }

    @Test
    void 깨진_JSON은_건너뛴다() {
        listener.onMessage("{broken");

        assertThat(captured).isEmpty();
    }

    @Test
    void 다른_종류의_쪽지는_지나친다() {
        // payment.events에 다른 이벤트가 실려도 이 컨슈머는 관심 밖
        listener.onMessage(envelope("PaymentRequested", "{\"reservationId\":501,\"scheduleId\":1}"));

        assertThat(captured).isEmpty();
    }

    @Test
    void apply의_예외는_밖으로_전파된다() {
        // 일시 장애(DB 순단 등)는 삼키지 않고 던져야 컨테이너가 재전달한다 — at-least-once의 전제
        PaymentResultListener throwing = new PaymentResultListener(cmd -> {
            throw new IllegalStateException("DB 순단");
        });

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> throwing.onMessage(
                        envelope("PaymentApproved", "{\"reservationId\":501,\"scheduleId\":1}")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 필수_필드가_빠지면_건너뛴다() {
        listener.onMessage(envelope("PaymentApproved", "{\"scheduleId\":1}")); // reservationId 없음
        listener.onMessage("""
                {"eventType":"PaymentApproved","payload":{"reservationId":501}}"""); // eventId 없음

        assertThat(captured).isEmpty();
    }
}
