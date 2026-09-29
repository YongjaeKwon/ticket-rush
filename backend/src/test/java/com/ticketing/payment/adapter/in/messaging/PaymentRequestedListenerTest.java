package com.ticketing.payment.adapter.in.messaging;

import com.ticketing.payment.application.port.in.ProcessPaymentUseCase.ProcessPaymentCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 리스너의 문지기 역할 — 무엇을 통과시키고 무엇을 조용히 넘기는지를 스프링·Kafka 없이 검증한다.
 * eventType 필터는 특히 중요하다: reservation.events에는 ReservationHeld 등 남의 쪽지가 같이 흐른다.
 */
class PaymentRequestedListenerTest {

    private final List<ProcessPaymentCommand> captured = new ArrayList<>();
    private final PaymentRequestedListener listener = new PaymentRequestedListener(captured::add);

    private static String envelope(String eventType, String payload) {
        return """
                {"eventId":"evt-1","eventType":"%s","version":1,\
                "occurredAt":"2026-09-11T05:00:00","aggregateId":"501","payload":%s}"""
                .formatted(eventType, payload);
    }

    @Test
    void 정상_쪽지는_필드가_그대로_커맨드로_추출된다() {
        listener.onMessage(envelope("PaymentRequested",
                "{\"reservationId\":501,\"scheduleId\":1,\"userId\":\"u-1\",\"amount\":134000}"));

        assertThat(captured).singleElement().isEqualTo(
                new ProcessPaymentCommand("evt-1", 501L, 1L, "u-1", 134_000));
    }

    @Test
    void 깨진_JSON은_건너뛴다() {
        listener.onMessage("{broken");

        assertThat(captured).isEmpty();
    }

    @Test
    void 다른_종류의_쪽지는_지나친다() {
        listener.onMessage(envelope("ReservationHeld",
                "{\"reservationId\":501,\"scheduleId\":1,\"userId\":\"u-1\",\"amount\":134000}"));

        assertThat(captured).isEmpty();
    }

    @Test
    void 필수_필드가_빠지면_건너뛴다() {
        listener.onMessage(envelope("PaymentRequested",
                "{\"reservationId\":501,\"scheduleId\":1,\"userId\":\"u-1\"}")); // amount 없음
        listener.onMessage(envelope("PaymentRequested",
                "{\"reservationId\":501,\"scheduleId\":1,\"userId\":7,\"amount\":134000}")); // userId 타입 오류

        assertThat(captured).isEmpty();
    }

    @Test
    void 양수가_아닌_amount는_건너뛴다() {
        // 통과시키면 도메인 불변식이 트랜잭션 밖에서 터져 재시도 루프로 샌다
        listener.onMessage(envelope("PaymentRequested",
                "{\"reservationId\":501,\"scheduleId\":1,\"userId\":\"u-1\",\"amount\":0}"));
        listener.onMessage(envelope("PaymentRequested",
                "{\"reservationId\":501,\"scheduleId\":1,\"userId\":\"u-1\",\"amount\":-100}"));
        listener.onMessage(envelope("PaymentRequested",
                "{\"reservationId\":501,\"scheduleId\":1,\"userId\":\"u-1\",\"amount\":10.5}"));

        assertThat(captured).isEmpty();
    }
}
