package com.ticketing.reservation.adapter.out.redis;

import com.ticketing.reservation.application.port.out.SeatHoldStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * hold:{scheduleId}:{seatId} 키를 SET NX EX로 선점한다.
 * NX: 키가 없을 때만 성공 → 같은 좌석의 동시 요청 중 한 명만 통과.
 * EX: TTL(5분) — 결제가 안 되면 키가 스스로 사라져 좌석이 풀린다.
 */
@Component
class RedisSeatHoldAdapter implements SeatHoldStore {

    private final StringRedisTemplate redis;

    RedisSeatHoldAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(long scheduleId, long seatId) {
        return "hold:" + scheduleId + ":" + seatId;
    }

    @Override
    public boolean tryHold(long scheduleId, long seatId, String userId, Duration ttl) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(key(scheduleId, seatId), userId, ttl));
    }

    @Override
    public void release(long scheduleId, long seatId) {
        redis.delete(key(scheduleId, seatId));
    }
}
