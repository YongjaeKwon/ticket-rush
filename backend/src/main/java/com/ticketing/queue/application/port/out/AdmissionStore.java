package com.ticketing.queue.application.port.out;

import java.time.Duration;
import java.util.Optional;

/** 입장 결과 저장소 (구현: Redis, TTL 10분 — 날아가도 다시 줄 서면 된다). */
public interface AdmissionStore {

    void save(long scheduleId, String userId, String token, Duration ttl);

    Optional<String> findToken(long scheduleId, String userId);
}
