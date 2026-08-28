package com.ticketing.catalog.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 스프링 없이 도는 순수 단위 테스트 — 2비트 패킹 계약(8-3) 검증. */
class SeatStatusBitmapTest {

    private SeatLayout layoutWithSeats(long sectionId, long firstSeatId, int count) {
        List<SeatLayout.SeatPosition> seats = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            seats.add(new SeatLayout.SeatPosition(firstSeatId + i, i / 25 + 1, i % 25 + 1));
        }
        return new SeatLayout(1L, List.of(new SeatLayout.SectionLayout(sectionId, "A", seats)));
    }

    @Test
    void 좌석_500개는_125바이트다() {
        SeatStatusBitmap bitmap = SeatStatusBitmap.of(layoutWithSeats(1, 1, 500), Set.of());

        assertThat(bitmap.sections()).hasSize(1);
        assertThat(bitmap.sections().getFirst().seatCount()).isEqualTo(500);
        assertThat(bitmap.sections().getFirst().bitmap()).hasSize(125);
    }

    @Test
    void 확정_좌석은_배치_순서_위치에_10으로_찍힌다() {
        // 좌석 id 1~8, 그중 3번째(index 2)와 8번째(index 7)가 확정
        SeatStatusBitmap bitmap = SeatStatusBitmap.of(layoutWithSeats(1, 1, 8), Set.of(3L, 8L));
        byte[] bytes = bitmap.sections().getFirst().bitmap();

        assertThat(bytes).hasSize(2);
        for (int i = 0; i < 8; i++) {
            int expected = (i == 2 || i == 7) ? SeatStatusBitmap.CONFIRMED : SeatStatusBitmap.FREE;
            assertThat(SeatStatusBitmap.statusAt(bytes, i)).as("seat index %d", i).isEqualTo(expected);
        }
        // 첫 바이트 = 00 00 10 00 → 0b00001000
        assertThat(bytes[0]).isEqualTo((byte) 0b00001000);
        // 둘째 바이트 = 00 00 00 10 → 0b00000010
        assertThat(bytes[1]).isEqualTo((byte) 0b00000010);
    }

    @Test
    void 좌석수가_4의_배수가_아니어도_바이트는_올림이다() {
        SeatStatusBitmap bitmap = SeatStatusBitmap.of(layoutWithSeats(1, 1, 5), Set.of());

        assertThat(bitmap.sections().getFirst().bitmap()).hasSize(2);
    }

    @Test
    void 모든_좌석이_확정이면_전부_10이다() {
        Set<Long> all = LongStream.rangeClosed(1, 12).boxed().collect(java.util.stream.Collectors.toSet());
        byte[] bytes = SeatStatusBitmap.of(layoutWithSeats(1, 1, 12), all).sections().getFirst().bitmap();

        for (int i = 0; i < 12; i++) {
            assertThat(SeatStatusBitmap.statusAt(bytes, i)).isEqualTo(SeatStatusBitmap.CONFIRMED);
        }
    }
}
