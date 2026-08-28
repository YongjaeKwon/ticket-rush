package com.ticketing.catalog.domain;

import java.util.List;

/**
 * 회차의 좌석 배치. 정적 데이터 — 좌석 순서(구역 오름차순 → 행 → 열)가
 * 상태 비트맵의 비트 순서와 계약이다 (ARCHITECTURE 8-3).
 */
public record SeatLayout(long scheduleId, List<SectionLayout> sections) {

    public record SectionLayout(long id, String name, List<SeatPosition> seats) {
    }

    public record SeatPosition(long id, int rowNo, int colNo) {
    }
}
