package com.ticketing.payment.adapter.out.persistence;

import com.ticketing.payment.application.port.out.ProcessedEventStore;
import com.ticketing.shared.messaging.ProcessedEventLedger;
import org.springframework.stereotype.Component;

/** 멱등 장부 — 실제 기록은 공용 장부(shared.messaging)에 위임한다. */
@Component
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
