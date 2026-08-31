package com.ticketing.queue.application.port.out;

import java.util.List;
import java.util.Optional;

/** 대기열 저장소 (구현: Redis ZSET — 점수가 진입 시각이라 먼저 온 순서가 유지된다). */
public interface WaitingQueue {

    /** 줄 서기. 이미 서 있으면 아무것도 안 바꾼다(원래 자리 유지). */
    void enterIfAbsent(long scheduleId, String userId, long enteredAtMillis);

    /** 0부터 시작하는 내 위치. 줄에 없으면 빈 값. */
    Optional<Long> rank(long scheduleId, String userId);

    /** 앞에서 n명을 꺼낸다(줄에서 제거됨). */
    List<String> popNext(long scheduleId, int n);

    /** 대기열이 존재하는 회차 목록 — 입장 스케줄러가 순회한다. */
    List<Long> activeScheduleIds();
}
