package com.ticketing.shared.event;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 봉투 계약 — 필드 구성과 JSON 형태가 곧 컨슈머와의 약속이다. */
class EventEnvelopeTest {

    private static final LocalDateTime OCCURRED = LocalDateTime.of(2026, 9, 10, 12, 0, 0);

    @Test
    void wrap은_eventId를_발급하고_현재_버전을_찍는다() {
        EventEnvelope envelope = EventEnvelope.wrap("ReservationHeld", "17", OCCURRED,
                Map.of("scheduleId", 1L, "seatId", 42L));

        assertThat(envelope.eventId()).isNotNull();
        assertThat(envelope.version()).isEqualTo(EventEnvelope.CURRENT_VERSION);
        assertThat(envelope.eventType()).isEqualTo("ReservationHeld");
        assertThat(envelope.aggregateId()).isEqualTo("17");
    }

    @Test
    void 봉투마다_eventId가_다르다() {
        EventEnvelope one = EventEnvelope.wrap("E", "1", OCCURRED, Map.of());
        EventEnvelope two = EventEnvelope.wrap("E", "1", OCCURRED, Map.of());

        assertThat(one.eventId()).isNotEqualTo(two.eventId());
    }

    @Test
    void JSON_직렬화_형태가_계약과_같다() {
        // 컨슈머·다른 서비스가 보게 될 와이어 형식 — 키 이름과 값 표현을 여기서 동결한다
        EventEnvelope envelope = EventEnvelope.wrap("ReservationHeld", "17",
                LocalDateTime.of(2026, 9, 10, 12, 0, 5), Map.of("scheduleId", 1L));
        JsonNode json = JsonMapper.builder().build().valueToTree(envelope);

        assertThat(json.get("eventId").asString())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(json.get("eventType").asString()).isEqualTo("ReservationHeld");
        assertThat(json.get("version").asInt()).isEqualTo(1);
        // UTC, 오프셋 없는 ISO-8601 문자열 (배열·타임스탬프 숫자가 아니라)
        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-09-10T12:00:05");
        assertThat(json.get("aggregateId").asString()).isEqualTo("17");
        assertThat(json.get("payload").get("scheduleId").asLong()).isEqualTo(1L);
    }

    @Test
    void 초가_0이어도_와이어에는_초가_보존된다() {
        // LocalDateTime.toString()은 초가 0이면 잘라내지만("12:00"), Jackson 직렬화는 보존한다.
        // 형식이 값에 따라 흔들리지 않으니 이쪽을 계약으로 동결한다
        EventEnvelope envelope = EventEnvelope.wrap("E", "1", OCCURRED, Map.of());
        JsonNode json = JsonMapper.builder().build().valueToTree(envelope);

        assertThat(json.get("occurredAt").asString()).isEqualTo("2026-09-10T12:00:00");
    }

    @Test
    void payload의_null_값은_키_이름을_알려주며_거부한다() {
        Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("pgTxId", null);

        assertThatThrownBy(() -> EventEnvelope.wrap("PaymentDeclined", "1", OCCURRED, withNull))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pgTxId");
    }

    @Test
    void payload는_봉투에_들어올_때_복사돼_바깥_변경이_스며들지_않는다() {
        Map<String, Object> mutable = new java.util.HashMap<>(Map.of("k", "v"));
        EventEnvelope envelope = EventEnvelope.wrap("E", "1", OCCURRED, mutable);

        mutable.put("k", "changed");

        assertThat(envelope.payload().get("k")).isEqualTo("v");
        assertThatThrownBy(() -> envelope.payload().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 빈_eventType과_버전_0은_거부한다() {
        assertThatThrownBy(() -> EventEnvelope.wrap(" ", "1", OCCURRED, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(java.util.UUID.randomUUID(), "E", 0, OCCURRED, "1", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 파티션_키는_scheduleId_문자열이다() {
        assertThat(Topics.partitionKey(1L)).isEqualTo("1");
        assertThat(Topics.RESERVATION_EVENTS).isEqualTo("reservation.events");
        assertThat(Topics.PAYMENT_EVENTS).isEqualTo("payment.events");
        assertThat(Topics.QUEUE_EVENTS).isEqualTo("queue.events");
    }
}
