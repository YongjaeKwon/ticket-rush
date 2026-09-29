package com.ticketing.payment.application.port.out;

import com.ticketing.shared.event.DomainEvent;

/** 결제 결과 이벤트 발행 — Outbox 경유, 릴레이가 payment.events로 보낸다. */
public interface PaymentEventPublisher {

    void publish(DomainEvent event);
}
