package com.ticketing.reservation.adapter.in.messaging;

import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase;
import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResult;
import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResultCommand;
import com.ticketing.shared.event.PaymentEventTypes;
import com.ticketing.shared.event.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * payment.events에서 결제 결과 3종(PaymentApproved/Declined/Failed)을 골라 예매에 반영한다.
 * 모르는 종류의 쪽지는 조용히 지나치고, 형식이 깨진 쪽지는 기록하고 넘어간다(재시도해도 같은 결과).
 * 재시도·DLT 정책은 체크리스트 8에서 한 번에 붙인다.
 */
@Component
class PaymentResultListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultListener.class);

    private final ApplyPaymentResultUseCase applyPaymentResult;
    private final JsonMapper json = JsonMapper.builder().build();

    PaymentResultListener(ApplyPaymentResultUseCase applyPaymentResult) {
        this.applyPaymentResult = applyPaymentResult;
    }

    @KafkaListener(topics = Topics.PAYMENT_EVENTS, groupId = "reservation",
            autoStartup = "${kafka.consumers.enabled:true}")
    void onMessage(String message) {
        JsonNode envelope;
        try {
            envelope = json.readTree(message);
        } catch (Exception e) {
            log.error("봉투 JSON을 읽을 수 없다 — 건너뜀: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").asString(null);
        PaymentResult result = switch (eventType) {
            case PaymentEventTypes.PAYMENT_APPROVED -> PaymentResult.APPROVED;
            case PaymentEventTypes.PAYMENT_DECLINED -> PaymentResult.DECLINED;
            case PaymentEventTypes.PAYMENT_FAILED -> PaymentResult.FAILED;
            case null, default -> null;
        };
        if (result == null) {
            log.debug("관심 밖 쪽지 — 지나침 (eventType={})", eventType);
            return;
        }
        JsonNode p = envelope.path("payload");
        if (!p.path("reservationId").isNumber() || !envelope.path("eventId").isString()) {
            log.error("결제 결과 쪽지에 필수 필드가 없다 — 건너뜀: {}", message);
            return;
        }
        applyPaymentResult.apply(new PaymentResultCommand(
                envelope.get("eventId").asString(), p.get("reservationId").asLong(), result));
    }
}
