package com.ticketing.reservation.adapter.out.persistence;

import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.shared.event.DomainEvent;
import com.ticketing.shared.messaging.OutboxWriter;
import org.springframework.stereotype.Component;

/**
 * "DB에 먼저 적고 나중에 보낸다" — 이벤트를 봉투에 넣어 outbox 테이블에 기록한다.
 * 예매 저장과 같은 트랜잭션이라, 커밋되면 이벤트도 반드시 함께 남고 롤백되면 함께 사라진다.
 * 실제 기록·검증은 공용 OutboxWriter가 하고, 전송은 릴레이가 한다.
 */
@Component
class OutboxEventPublisherAdapter implements EventPublisher {

    private final OutboxWriter outbox;

    OutboxEventPublisherAdapter(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publish(DomainEvent event) {
        outbox.write("RESERVATION", event);
    }
}
