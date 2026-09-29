package com.ticketing.shared.messaging;

import com.ticketing.shared.event.DomainEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 릴레이가 보낼 수 없는 봉투(poison row)를 서랍 입구에서 막는지 — DB 없이 검증한다. */
class OutboxWriterTest {

    private static DomainEvent event(Map<String, Object> payload) {
        return new DomainEvent() {
            @Override public String eventType() { return "Broken"; }
            @Override public long aggregateId() { return 1L; }
            @Override public LocalDateTime occurredAt() { return LocalDateTime.of(2026, 9, 11, 0, 0); }
            @Override public Map<String, Object> payload() { return payload; }
        };
    }

    @Test
    void scheduleId_없는_이벤트는_서랍에_들어가기_전에_거부된다() {
        OutboxWriter writer = new OutboxWriter(null);

        assertThatThrownBy(() -> writer.write("RESERVATION", event(Map.of("seatId", 9L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheduleId");
    }

    @Test
    void 릴레이가_모르는_aggregate_type도_거부된다() {
        OutboxWriter writer = new OutboxWriter(null);

        // 오타 하나가 릴레이 전체를 세우는 poison row가 되지 않게 입구에서 막는다
        assertThatThrownBy(() -> writer.write("PAYMENTS", event(Map.of("scheduleId", 1L))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate_type");
    }
}
