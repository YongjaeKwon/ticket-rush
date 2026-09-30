package com.ticketing.shared.messaging;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 멱등 장부의 공용 구현 — 복합 PK (consumer, event_id)가 "두 번 기록"을 물리적으로 막는다.
 * 모듈마다 포트·어댑터는 각자 두되(헥사고날), 실제 SQL과 중복 키 번역은 여기 한 곳이라
 * 컨슈머가 늘어도 장부 의미가 갈리지 않는다. OutboxWriter와 같은 배치의 생각이다.
 */
@Component
public class ProcessedEventLedger {

    private final JdbcClient jdbc;
    private final Clock clock;

    ProcessedEventLedger(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 부작용 없는 선확인 — 이미 처리한 쪽지면 true. */
    public boolean alreadyProcessed(String consumer, String eventId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM processed_event WHERE consumer = :consumer AND event_id = :eventId")
                .param("consumer", consumer).param("eventId", eventId)
                .query(Long.class).single();
        return count > 0;
    }

    /** 장부에 적는다 — 반드시 처리와 같은 트랜잭션에서. 누가 먼저 적었으면 false(호출자가 롤백). */
    public boolean markProcessed(String consumer, String eventId) {
        try {
            jdbc.sql("INSERT INTO processed_event (consumer, event_id, processed_at) VALUES (:consumer, :eventId, :at)")
                    .param("consumer", consumer).param("eventId", eventId)
                    .param("at", LocalDateTime.now(clock))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
