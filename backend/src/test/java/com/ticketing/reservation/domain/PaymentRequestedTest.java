package com.ticketing.reservation.domain;

import com.ticketing.shared.event.PaymentEventTypes;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 결제 요청 쪽지의 가드 — payment 쪽 문지기가 조용히 버릴 쪽지를 발행 쪽에서 먼저 막는다. */
class PaymentRequestedTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 30, 11, 0, 0);

    private static Reservation saved() {
        return Reservation.reconstitute(42L, 1L, 17L, "user-1", ReservationStatus.HELD,
                NOW.plusMinutes(5), 0L, NOW, PaymentStatus.REQUESTED, null);
    }

    @Test
    void 금액이_양수가_아니면_만들_수_없다_예매가_영원히_결제_중으로_남지_않게() {
        assertThatThrownBy(() -> PaymentRequested.from(saved(), 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentRequested.from(saved(), -1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 저장되지_않은_예매로는_만들_수_없다() {
        Reservation unsaved = Reservation.hold(1L, 17L, "user-1", NOW, Duration.ofMinutes(5));

        assertThatThrownBy(() -> PaymentRequested.from(unsaved, 134_000, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void payload는_payment_쪽_문지기가_요구하는_네_칸을_싣는다() {
        PaymentRequested event = PaymentRequested.from(saved(), 134_000, NOW);

        assertThat(event.eventType()).isEqualTo(PaymentEventTypes.PAYMENT_REQUESTED);
        assertThat(event.aggregateId()).isEqualTo(42L);
        assertThat(event.payload())
                .containsEntry("reservationId", 42L)
                .containsEntry("scheduleId", 1L)   // 파티션 키
                .containsEntry("userId", "user-1")
                .containsEntry("amount", 134_000);
    }
}
