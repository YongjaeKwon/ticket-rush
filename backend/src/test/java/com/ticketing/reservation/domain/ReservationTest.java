package com.ticketing.reservation.domain;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** 순수 단위 테스트 — 스프링·JPA 없이 상태 전이 규칙 전체를 조인다. */
class ReservationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 7, 11, 0, 0);
    private static final Duration HOLD_FOR = Duration.ofMinutes(5);

    private Reservation heldReservation() {
        return Reservation.hold(1L, 17L, "user-1", NOW, HOLD_FOR);
    }

    @Test
    void 홀드하면_HELD_상태에_만료시각은_5분_뒤다() {
        Reservation reservation = heldReservation();

        assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.expiresAt()).isEqualTo(NOW.plusMinutes(5));
        assertThat(reservation.createdAt()).isEqualTo(NOW);
        assertThat(reservation.id()).isNull();   // 저장 전
    }

    @Nested
    class Confirm {

        @Test
        void 만료_전이면_확정된다() {
            Reservation reservation = heldReservation();

            reservation.confirm(NOW.plusMinutes(4), "mock-tx");

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(reservation.paymentTransactionId()).isEqualTo("mock-tx");
        }

        @Test
        void 만료시각_정각까지는_확정할_수_있다() {
            Reservation reservation = heldReservation();

            reservation.confirm(NOW.plusMinutes(5), "mock-tx");

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        void 만료_후_확정은_HOLD_EXPIRED로_거부된다() {
            Reservation reservation = heldReservation();

            ReservationException e = catchThrowableOfType(ReservationException.class,
                    () -> reservation.confirm(NOW.plusMinutes(5).plusSeconds(1), "mock-tx"));

            assertThat(e.code()).isEqualTo("HOLD_EXPIRED");
            assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);   // 상태는 안 바뀐다
        }

        @Test
        void 확정은_최종_상태라_다시_확정할_수_없다() {
            Reservation reservation = heldReservation();
            reservation.confirm(NOW, "mock-tx");

            assertThatThrownBy(() -> reservation.confirm(NOW, "mock-tx"))
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATE");
        }
    }

    @Nested
    class Expire {

        @Test
        void HELD는_만료시킬_수_있다() {
            Reservation reservation = heldReservation();

            reservation.expire();

            assertThat(reservation.status()).isEqualTo(ReservationStatus.EXPIRED);
        }

        @Test
        void 확정된_예매는_만료시킬_수_없다() {
            Reservation reservation = heldReservation();
            reservation.confirm(NOW, "mock-tx");

            assertThatThrownBy(reservation::expire)
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATE");
        }
    }

    @Nested
    class Cancel {

        @Test
        void HELD는_취소할_수_있다() {
            Reservation reservation = heldReservation();

            reservation.cancel();

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CANCELLED);
        }

        @Test
        void 취소된_예매는_어떤_전이도_안_된다() {
            Reservation reservation = heldReservation();
            reservation.cancel();

            assertThatThrownBy(() -> reservation.confirm(NOW, "mock-tx"))
                    .isInstanceOf(ReservationException.class);
            assertThatThrownBy(reservation::expire)
                    .isInstanceOf(ReservationException.class);
            assertThatThrownBy(reservation::cancel)
                    .isInstanceOf(ReservationException.class);
        }

        @Test
        void 확정된_예매는_취소할_수_없다_환불은_범위_밖() {
            Reservation reservation = heldReservation();
            reservation.confirm(NOW, "mock-tx");

            assertThatThrownBy(reservation::cancel)
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATE");
        }
    }

    /** 3단계 비동기 확정 — 결제 진행 상태 (ADR 0009) */
    @Nested
    class PaymentAttempt {

        @Test
        void 홀드_직후에는_결제_상태가_없다() {
            assertThat(heldReservation().paymentStatus()).isNull();
        }

        @Test
        void 결제를_요청하면_HELD_그대로_REQUESTED가_된다() {
            Reservation reservation = heldReservation();

            reservation.requestPayment(NOW.plusMinutes(1));

            assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.REQUESTED);
        }

        @Test
        void 결제_중에_다시_요청하면_PAYMENT_IN_PROGRESS로_거부된다_이중_결제_방지() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);

            assertThatThrownBy(() -> reservation.requestPayment(NOW))
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "PAYMENT_IN_PROGRESS");
        }

        @Test
        void 만료된_홀드는_결제를_요청할_수_없다() {
            Reservation reservation = heldReservation();

            assertThatThrownBy(() -> reservation.requestPayment(NOW.plusMinutes(5).plusSeconds(1)))
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "HOLD_EXPIRED");
            assertThat(reservation.paymentStatus()).isNull();
        }

        @Test
        void 거절되면_HELD_그대로_DECLINED가_되고_새로_요청할_수_있다() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);

            reservation.declinePayment();

            assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.DECLINED);
            reservation.requestPayment(NOW);   // 재시도 허용
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.REQUESTED);
        }

        @Test
        void 실패하면_되돌리기로_EXPIRED와_FAILED가_된다() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);

            reservation.failPayment();

            assertThat(reservation.status()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        void 결제_중에는_취소할_수_없다_취소_뒤_승인이_오면_돈만_나간다() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);

            assertThatThrownBy(reservation::cancel)
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "PAYMENT_IN_PROGRESS");
            assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
        }

        @Test
        void 승인됐지만_좌석을_놓치면_EXPIRED와_APPROVED로_닫히고_승인번호는_남는다() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);

            reservation.loseSeat("mock-tx");

            assertThat(reservation.status()).isEqualTo(ReservationStatus.EXPIRED);
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(reservation.paymentTransactionId()).isEqualTo("mock-tx");
        }

        @Test
        void 확정된_예매는_다시_결제를_요청할_수_없다() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);
            reservation.confirm(NOW, "mock-tx");

            assertThatThrownBy(() -> reservation.requestPayment(NOW.plusMinutes(1)))
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATE");
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.APPROVED);
        }

        @Test
        void 결제_실패로_풀린_예매는_다시_결제를_요청할_수_없다() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);
            reservation.failPayment();

            assertThatThrownBy(() -> reservation.requestPayment(NOW.plusMinutes(1)))
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATE");
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        void 취소된_예매는_결제를_요청할_수_없다() {
            Reservation reservation = heldReservation();
            reservation.cancel();

            assertThatThrownBy(() -> reservation.requestPayment(NOW))
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATE");
        }

        @Test
        void 확정된_예매에_늦은_거절이_와도_승인_표지는_그대로다() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);
            reservation.confirm(NOW, "mock-tx");

            assertThatThrownBy(reservation::declinePayment)
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATE");
            assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.APPROVED);
        }

        @Test
        void 거절된_뒤에는_취소할_수_있다() {
            Reservation reservation = heldReservation();
            reservation.requestPayment(NOW);
            reservation.declinePayment();

            reservation.cancel();

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CANCELLED);
        }
    }

    @Test
    void reconstitute는_DB_행을_그대로_복원한다() {
        Reservation reservation = Reservation.reconstitute(42L, 1L, 17L, "user-1",
                ReservationStatus.CONFIRMED, NOW.plusMinutes(5), 3L, NOW, PaymentStatus.APPROVED, "mock-tx");

        assertThat(reservation.id()).isEqualTo(42L);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.version()).isEqualTo(3L);
        assertThat(reservation.paymentStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(reservation.paymentTransactionId()).isEqualTo("mock-tx");
        assertThat(reservation.isHeld()).isFalse();
    }
}
