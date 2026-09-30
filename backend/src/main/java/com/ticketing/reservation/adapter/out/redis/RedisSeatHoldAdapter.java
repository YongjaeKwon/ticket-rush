package com.ticketing.reservation.adapter.out.redis;

import com.ticketing.reservation.application.port.out.SeatHoldStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * hold:{scheduleId}:{seatId} 키를 SET NX EX로 선점한다. 값은 userId.
 * NX: 키가 없을 때만 성공 → 같은 좌석의 동시 요청 중 한 명만 통과.
 * EX: TTL(5분) — 결제가 안 되면 키가 스스로 사라져 좌석이 풀린다.
 *
 * 해제는 비교-삭제(Lua): 값이 내 userId일 때만 DEL. 만료 직전 확정·실패 처리가 커밋을
 * 끝내는 사이 TTL이 지나고 다른 사용자가 새로 선점했다면, 그 키는 남의 것이라 지우지 않는다.
 */
@Component
class RedisSeatHoldAdapter implements SeatHoldStore {

    // GET-비교-DEL을 한 번에 — 비교와 삭제 사이에 키가 바뀌는 틈을 없앤다
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

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
    public void release(long scheduleId, long seatId, String userId) {
        // ponytail: userId 비교라 같은 사용자가 같은 좌석을 연속 재선점하면 신구 홀드를 구분 못 한다
        // — 교차 사용자 피해는 막히므로 홀드별 고유 토큰은 backlog
        redis.execute(COMPARE_AND_DELETE, List.of(key(scheduleId, seatId)), userId);
    }
}
