package com.ticketing.reservation.adapter.in.scheduler;

import com.ticketing.reservation.application.port.in.ExpireHoldUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 10초마다 만료된 홀드를 정리한다 (ARCHITECTURE 4-3 "만료" 2차 방어).
 * 통합 테스트에서는 hold-expiry.enabled=false로 끈다 — 시각을 조작하는
 * 테스트와 경쟁하지 않게 하기 위해서다.
 */
@Component
@ConditionalOnProperty(name = "hold-expiry.enabled", havingValue = "true", matchIfMissing = true)
class HoldExpiryScheduler {

    private final ExpireHoldUseCase expireHold;

    HoldExpiryScheduler(ExpireHoldUseCase expireHold) {
        this.expireHold = expireHold;
    }

    @Scheduled(fixedDelay = 10_000)
    void expireOverdueHolds() {
        expireHold.expireOverdue();
    }
}
