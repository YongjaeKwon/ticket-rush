package com.ticketing.queue.application.port.in;

/** 대기열 앞에서 N명씩 입장시킨다. 스케줄러가 1초마다 부른다. */
public interface AdmitWaitingUseCase {

    /** 이번 호출로 입장시킨 인원 수. */
    int admitNext();
}
