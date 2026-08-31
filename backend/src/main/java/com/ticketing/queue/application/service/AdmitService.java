package com.ticketing.queue.application.service;

import com.ticketing.queue.application.port.in.AdmitWaitingUseCase;
import com.ticketing.queue.application.port.out.AdmissionStore;
import com.ticketing.queue.application.port.out.AdmissionTokenIssuer;
import com.ticketing.queue.application.port.out.WaitingQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 입장 처리 — 대기열 앞에서 batch-size명씩 꺼내 입장권을 준다.
 * N을 얼마로 두느냐가 DB를 지키는 밸브다(4단계에서 값별 에러율 비교 예정).
 */
@Service
public class AdmitService implements AdmitWaitingUseCase {

    private final WaitingQueue waitingQueue;
    private final AdmissionStore admissionStore;
    private final AdmissionTokenIssuer tokenIssuer;
    private final int batchSize;
    private final Duration tokenTtl;

    public AdmitService(WaitingQueue waitingQueue, AdmissionStore admissionStore,
                        AdmissionTokenIssuer tokenIssuer,
                        @Value("${queue.admission.batch-size:100}") int batchSize,
                        @Value("${admission.jwt.ttl:10m}") Duration tokenTtl) {
        this.waitingQueue = waitingQueue;
        this.admissionStore = admissionStore;
        this.tokenIssuer = tokenIssuer;
        this.batchSize = batchSize;
        this.tokenTtl = tokenTtl;
    }

    @Override
    public int admitNext() {
        int admitted = 0;
        for (long scheduleId : waitingQueue.activeScheduleIds()) {
            List<String> users = waitingQueue.popNext(scheduleId, batchSize);
            for (String userId : users) {
                admissionStore.save(scheduleId, userId, tokenIssuer.issue(scheduleId, userId), tokenTtl);
            }
            admitted += users.size();
        }
        return admitted;
    }
}
