package com.ticketing.payment.adapter.in.messaging;

import com.ticketing.payment.application.port.in.ProcessPaymentUseCase;
import com.ticketing.payment.application.port.in.ProcessPaymentUseCase.ProcessPaymentCommand;
import com.ticketing.shared.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * reservation.events에서 PaymentRequested 쪽지만 골라 결제를 시작한다 (ADR 0008).
 * 같은 토픽의 다른 쪽지(ReservationHeld 등)는 조용히 지나친다 — 토픽은 발행자별,
 * 무엇을 읽을지는 소비자가 고른다.
 *
 * 형식이 깨진 쪽지는 기록하고 넘어간다(막지 않음) — 재시도해도 같은 결과라서다.
 * 재시도·DLT 정책은 다음 단위(체크리스트 8)에서 한 번에 붙인다.
 */
@Component
class PaymentRequestedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestedListener.class);

    private final ProcessPaymentUseCase processPayment;
    private final JsonMapper json = JsonMapper.builder().build();

    PaymentRequestedListener(ProcessPaymentUseCase processPayment) {
        this.processPayment = processPayment;
    }

    @KafkaListener(topics = Topics.RESERVATION_EVENTS, groupId = "payment",
            autoStartup = "${kafka.consumers.enabled:true}")
    void onMessage(String message) {
        JsonNode envelope;
        try {
            envelope = json.readTree(message);
        } catch (Exception e) {
            log.error("봉투 JSON을 읽을 수 없다 — 건너뜀: {}", message, e);
            return;
        }
        if (!"PaymentRequested".equals(envelope.path("eventType").asString(null))) {
            return;
        }
        JsonNode p = envelope.path("payload");
        // amount는 양수 정수까지 여기서 막는다 — 통과시키면 도메인 불변식(Payment 생성자)이
        // 트랜잭션 밖에서 터져 재시도 루프로 새고, PG만 헛돌게 된다
        if (!p.path("reservationId").isNumber() || !p.path("scheduleId").isNumber()
                || !p.path("amount").isIntegralNumber() || p.path("amount").asInt() <= 0
                || !p.path("userId").isString() || !envelope.path("eventId").isString()) {
            log.error("PaymentRequested 쪽지에 필수 필드가 없거나 값이 유효하지 않다 — 건너뜀: {}", message);
            return;
        }
        processPayment.process(new ProcessPaymentCommand(
                envelope.get("eventId").asString(),
                p.get("reservationId").asLong(),
                p.get("scheduleId").asLong(),
                p.get("userId").asString(),
                p.get("amount").asInt()));
    }
}
