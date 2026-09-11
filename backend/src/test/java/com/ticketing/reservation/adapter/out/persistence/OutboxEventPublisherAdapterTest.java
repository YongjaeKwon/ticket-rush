package com.ticketing.reservation.adapter.out.persistence;

import com.ticketing.reservation.domain.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 파티션 키 규약(payload에 scheduleId)을 서랍 입구에서 막는지 — DB 없이 검증한다. */
class OutboxEventPublisherAdapterTest {

    @Test
    void scheduleId_없는_이벤트는_서랍에_들어가기_전에_거부된다() {
        OutboxEventPublisherAdapter adapter = new OutboxEventPublisherAdapter(null);
        DomainEvent broken = new DomainEvent() {
            @Override public String eventType() { return "Broken"; }
            @Override public long aggregateId() { return 1L; }
            @Override public LocalDateTime occurredAt() { return LocalDateTime.of(2026, 9, 11, 0, 0); }
            @Override public Map<String, Object> payload() { return Map.of("seatId", 9L); }
        };

        assertThatThrownBy(() -> adapter.publish(broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduleId");
    }
}
