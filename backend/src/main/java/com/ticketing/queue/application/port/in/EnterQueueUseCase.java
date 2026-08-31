package com.ticketing.queue.application.port.in;

/** 대기열에 줄을 선다. 이미 서 있으면 원래 순번을 유지한다. */
public interface EnterQueueUseCase {

    /** 1부터 시작하는 내 순번을 돌려준다. */
    long enter(long scheduleId, String userId);
}
