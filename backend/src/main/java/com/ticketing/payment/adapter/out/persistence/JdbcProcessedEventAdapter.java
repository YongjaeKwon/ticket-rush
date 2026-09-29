package com.ticketing.payment.adapter.out.persistence;

import com.ticketing.payment.application.port.out.ProcessedEventStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/** 멱등 장부 — 복합 PK (consumer, event_id)가 "두 번 기록"을 물리적으로 막는다. */
@Component
class JdbcProcessedEventAdapter implements ProcessedEventStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcProcessedEventAdapter(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean alreadyProcessed(String consumer, String eventId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE consumer = :consumer AND event_id = :eventId")
                .param("consumer", consumer).param("eventId", eventId)
                .query(Long.class).single();
        return count > 0;
    }

    @Override
    public boolean markProcessed(String consumer, String eventId) {
        try {
            jdbc.sql("INSERT INTO processed_event (consumer, event_id, processed_at) VALUES (:consumer, :eventId, :at)")
                    .param("consumer", consumer).param("eventId", eventId)
                    .param("at", LocalDateTime.now(clock))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false; // 누가 먼저 적었다 — 호출자가 롤백한다
        }
    }
}
