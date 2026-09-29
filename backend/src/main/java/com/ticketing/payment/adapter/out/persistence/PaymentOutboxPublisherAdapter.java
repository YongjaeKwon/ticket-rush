package com.ticketing.payment.adapter.out.persistence;

import com.ticketing.payment.application.port.out.PaymentEventPublisher;
import com.ticketing.shared.event.DomainEvent;
import com.ticketing.shared.messaging.OutboxWriter;
import org.springframework.stereotype.Component;

/** 결제 결과도 서랍에 먼저 — 릴레이가 payment.events로 실어 보낸다. */
@Component
class PaymentOutboxPublisherAdapter implements PaymentEventPublisher {

    private final OutboxWriter outbox;

    PaymentOutboxPublisherAdapter(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publish(DomainEvent event) {
        outbox.write("PAYMENT", event);
    }
}
