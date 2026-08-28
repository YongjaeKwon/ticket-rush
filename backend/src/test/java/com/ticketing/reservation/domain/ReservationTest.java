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

            reservation.confirm(NOW.plusMinutes(4));

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        void 만료시각_정각까지는_확정할_수_있다() {
            Reservation reservation = heldReservation();

            reservation.confirm(NOW.plusMinutes(5));

            assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        void 만료_후_확정은_HOLD_EXPIRED로_거부된다() {
            Reservation reservation = heldReservation();

            ReservationException e = catchThrowableOfType(ReservationException.class,
                    () -> reservation.confirm(NOW.plusMinutes(5).plusSeconds(1)));

            assertThat(e.code()).isEqualTo("HOLD_EXPIRED");
            assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);   // 상태는 안 바뀐다
        }

        @Test
        void 확정은_최종_상태라_다시_확정할_수_없다() {
            Reservation reservation = heldReservation();
            reservation.confirm(NOW);

            assertThatThrownBy(() -> reservation.confirm(NOW))
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
            reservation.confirm(NOW);

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

            assertThatThrownBy(() -> reservation.confirm(NOW))
                    .isInstanceOf(ReservationException.class);
            assertThatThrownBy(reservation::expire)
                    .isInstanceOf(ReservationException.class);
            assertThatThrownBy(reservation::cancel)
                    .isInstanceOf(ReservationException.class);
        }

        @Test
        void 확정된_예매는_취소할_수_없다_환불은_범위_밖() {
            Reservation reservation = heldReservation();
            reservation.confirm(NOW);

            assertThatThrownBy(reservation::cancel)
                    .isInstanceOf(ReservationException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_RESERVATION_STATE");
        }
    }

    @Test
    void reconstitute는_DB_행을_그대로_복원한다() {
        Reservation reservation = Reservation.reconstitute(42L, 1L, 17L, "user-1",
                ReservationStatus.CONFIRMED, NOW.plusMinutes(5), 3L, NOW);

        assertThat(reservation.id()).isEqualTo(42L);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.version()).isEqualTo(3L);
        assertThat(reservation.isHeld()).isFalse();
    }
}
