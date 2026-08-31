package com.ticketing.queue.adapter.out.redis;

import com.ticketing.queue.application.port.out.WaitingQueue;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * queue:{scheduleId} ZSET — 점수 = 진입 시각(ms)이라 오름차순이 곧 선착순.
 * ZADD NX(있으면 무시)로 재진입해도 원래 자리를 지키고, ZPOPMIN으로 앞에서 꺼낸다.
 */
@Component
class RedisWaitingQueueAdapter implements WaitingQueue {

    private final StringRedisTemplate redis;

    RedisWaitingQueueAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(long scheduleId) {
        return "queue:" + scheduleId;
    }

    @Override
    public void enterIfAbsent(long scheduleId, String userId, long enteredAtMillis) {
        redis.opsForZSet().addIfAbsent(key(scheduleId), userId, enteredAtMillis);
    }

    @Override
    public Optional<Long> rank(long scheduleId, String userId) {
        return Optional.ofNullable(redis.opsForZSet().rank(key(scheduleId), userId));
    }

    @Override
    public List<String> popNext(long scheduleId, int n) {
        Set<ZSetOperations.TypedTuple<String>> popped = redis.opsForZSet().popMin(key(scheduleId), n);
        if (popped == null) {
            return List.of();
        }
        return popped.stream().map(ZSetOperations.TypedTuple::getValue).toList();
    }

    @Override
    public List<Long> activeScheduleIds() {
        // KEYS는 전체 차단이라 쓰지 않고 SCAN으로 훑는다
        List<Long> ids = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match("queue:*").count(100).build())) {
            while (cursor.hasNext()) {
                ids.add(Long.parseLong(cursor.next().substring("queue:".length())));
            }
        }
        return ids;
    }
}
