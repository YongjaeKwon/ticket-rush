package com.ticketing.reservation.application.port.in;

/** 만료 시각이 지난 HELD 예매를 EXPIRED로 정리한다 (2차 방어 — 1차는 Redis TTL). */
public interface ExpireHoldUseCase {

    /** 처리한 건수를 돌려준다. 호출 한 번에 최대 100건. */
    int expireOverdue();
}
