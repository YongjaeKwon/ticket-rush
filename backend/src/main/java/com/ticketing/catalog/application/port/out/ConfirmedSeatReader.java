package com.ticketing.catalog.application.port.out;

import java.util.Set;

/**
 * 확정 좌석 조회. confirmed_seat는 reservation의 사실이지만
 * 1단계에서는 읽기 전용 프로젝션으로 직접 본다 — 근거는 docs/adr/0004.
 */
public interface ConfirmedSeatReader {

    Set<Long> confirmedSeatIds(long scheduleId);
}
