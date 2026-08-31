package com.ticketing.catalog.adapter.out.redis;

import com.ticketing.catalog.application.port.out.SeatHoldReader;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** hold:{scheduleId}:{seatId} 키를 SCAN으로 훑어 홀드 중인 좌석을 모은다. */
@Component
class RedisSeatHoldReaderAdapter implements SeatHoldReader {

    private final StringRedisTemplate redis;

    RedisSeatHoldReaderAdapter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Set<Long> heldSeatIds(long scheduleId) {
        String prefix = "hold:" + scheduleId + ":";
        Set<Long> seatIds = new HashSet<>();
        try (Cursor<String> cursor = redis.scan(
                ScanOptions.scanOptions().match(prefix + "*").count(500).build())) {
            while (cursor.hasNext()) {
                seatIds.add(Long.parseLong(cursor.next().substring(prefix.length())));
            }
        }
        return seatIds;
    }
}
