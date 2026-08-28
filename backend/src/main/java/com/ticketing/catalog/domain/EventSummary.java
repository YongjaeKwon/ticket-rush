package com.ticketing.catalog.domain;

import java.time.LocalDateTime;

/** 공연 목록 한 줄. 시각은 UTC 기준 LocalDateTime. */
public record EventSummary(long id, String title, String venue, LocalDateTime openAt) {
}
