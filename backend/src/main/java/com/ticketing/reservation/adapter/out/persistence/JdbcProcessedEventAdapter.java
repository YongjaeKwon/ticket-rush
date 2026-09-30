package com.ticketing.reservation.adapter.out.persistence;

import com.ticketing.reservation.application.port.out.ProcessedEventStore;
import com.ticketing.shared.messaging.ProcessedEventLedger;
import org.springframework.stereotype.Component;

/** 멱등 장부 — 실제 기록은 공용 장부(shared.messaging)에 위임한다. */
@Component("reservationProcessedEventAdapter") // payment 쪽 동명 클래스와 빈 이름 충돌 방지
class JdbcProcessedEventAdapter implements ProcessedEventStore {

    private final ProcessedEventLedger ledger;

    JdbcProcessedEventAdapter(ProcessedEventLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public boolean alreadyProcessed(String consumer, String eventId) {
        return ledger.alreadyProcessed(consumer, eventId);
    }

    @Override
    public boolean markProcessed(String consumer, String eventId) {
        return ledger.markProcessed(consumer, eventId);
    }
}
