package com.ticketing.catalog.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 구역별 좌석 상태 비트맵 (ARCHITECTURE 8-3).
 * 좌석당 2비트: 00 빈자리, 01 홀드, 10 확정, 11 판매불가.
 * 비트 순서 = 배치의 좌석 순서. 한 바이트에 4좌석, 상위 비트부터 채운다
 * (i번째 좌석 = bitmap[i/4]의 (6 - (i%4)*2) 시프트 위치).
 */
public record SeatStatusBitmap(long scheduleId, List<SectionBitmap> sections) {

    public static final int FREE = 0b00;
    public static final int HELD = 0b01;
    public static final int CONFIRMED = 0b10;
    public static final int BLOCKED = 0b11;

    public record SectionBitmap(long sectionId, int seatCount, byte[] bitmap) {
    }

    /** 배치 순서를 기준으로 확정·홀드 좌석을 반영해 비트맵을 만든다. 확정이 홀드보다 우선. */
    public static SeatStatusBitmap of(SeatLayout layout, Set<Long> confirmedSeatIds,
                                      Set<Long> heldSeatIds) {
        List<SectionBitmap> sections = new ArrayList<>();
        for (SeatLayout.SectionLayout section : layout.sections()) {
            List<SeatLayout.SeatPosition> seats = section.seats();
            byte[] bitmap = new byte[(seats.size() + 3) / 4];
            for (int i = 0; i < seats.size(); i++) {
                long seatId = seats.get(i).id();
                int status = confirmedSeatIds.contains(seatId) ? CONFIRMED
                        : heldSeatIds.contains(seatId) ? HELD
                        : FREE;
                bitmap[i / 4] |= (byte) (status << (6 - (i % 4) * 2));
            }
            sections.add(new SectionBitmap(section.id(), seats.size(), bitmap));
        }
        return new SeatStatusBitmap(layout.scheduleId(), sections);
    }

    /** i번째 좌석의 상태를 읽는다. 테스트·디코딩 참조 구현. */
    public static int statusAt(byte[] bitmap, int index) {
        return (bitmap[index / 4] >> (6 - (index % 4) * 2)) & 0b11;
    }
}
