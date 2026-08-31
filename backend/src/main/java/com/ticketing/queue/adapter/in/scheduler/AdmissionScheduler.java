package com.ticketing.queue.adapter.in.scheduler;

import com.ticketing.queue.application.port.in.AdmitWaitingUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 1초마다 대기열 앞에서 N명씩 입장. 테스트에서는 queue.admission.enabled=false로 끈다. */
@Component
@ConditionalOnProperty(name = "queue.admission.enabled", havingValue = "true", matchIfMissing = true)
class AdmissionScheduler {

    private final AdmitWaitingUseCase admitWaiting;

    AdmissionScheduler(AdmitWaitingUseCase admitWaiting) {
        this.admitWaiting = admitWaiting;
    }

    @Scheduled(fixedDelay = 1_000)
    void admit() {
        admitWaiting.admitNext();
    }
}
