package com.ticketing.catalog.domain;

import java.time.LocalDateTime;

/**
 * 회차 하나 + 소속 공연 요약. 결제·완료 화면이 예매(scheduleId만 안다)에서
 * 공연명·장소·일시를 거꾸로 찾아갈 때 쓴다.
 */
public record ScheduleDetail(
        long id,
        LocalDateTime startsAt,
        long eventId,
        String eventTitle,
        String venue
) {
}
