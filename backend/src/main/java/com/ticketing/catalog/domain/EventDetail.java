package com.ticketing.catalog.domain;

import java.time.LocalDateTime;
import java.util.List;

/** 공연 상세 = 요약 + 회차 목록. */
public record EventDetail(
        long id,
        String title,
        String venue,
        LocalDateTime openAt,
        List<ScheduleSummary> schedules
) {
    public record ScheduleSummary(long id, LocalDateTime startsAt) {
    }
}
