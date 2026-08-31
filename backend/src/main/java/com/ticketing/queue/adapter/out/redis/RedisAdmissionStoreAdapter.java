package com.ticketing.queue.adapter.out.redis;

import com.ticketing.queue.application.port.out.AdmissionStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/** admitted:{scheduleId}:{userId} = 입장권 JWT, TTL 10분. */
@Component
class RedisAdmissionStoreAdapter implements AdmissionStore {

    private final StringRedisTemplate redis;

    RedisAdmissionStoreAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(long scheduleId, String userId) {
        return "admitted:" + scheduleId + ":" + userId;
    }

    @Override
    public void save(long scheduleId, String userId, String token, Duration ttl) {
        redis.opsForValue().set(key(scheduleId, userId), token, ttl);
    }

    @Override
    public Optional<String> findToken(long scheduleId, String userId) {
        return Optional.ofNullable(redis.opsForValue().get(key(scheduleId, userId)));
    }
}
