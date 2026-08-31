package com.ticketing.queue.application.service;

import com.ticketing.queue.application.port.in.EnterQueueUseCase;
import com.ticketing.queue.application.port.in.GetQueueStatusUseCase;
import com.ticketing.queue.application.port.out.AdmissionStore;
import com.ticketing.queue.application.port.out.WaitingQueue;
import org.springframework.stereotype.Service;

import java.time.Clock;

/** 줄 서기와 내 상태 조회. 상태는 DB가 아니라 전부 Redis에 있다 — 날아가면 다시 줄 서면 된다. */
@Service
public class QueueService implements EnterQueueUseCase, GetQueueStatusUseCase {

    private final WaitingQueue waitingQueue;
    private final AdmissionStore admissionStore;
    private final Clock clock;

    public QueueService(WaitingQueue waitingQueue, AdmissionStore admissionStore, Clock clock) {
        this.waitingQueue = waitingQueue;
        this.admissionStore = admissionStore;
        this.clock = clock;
    }

    @Override
    public long enter(long scheduleId, String userId) {
        // 이미 입장한 사용자는 줄에 다시 세우지 않는다
        if (admissionStore.findToken(scheduleId, userId).isPresent()) {
            return 0;
        }
        waitingQueue.enterIfAbsent(scheduleId, userId, clock.millis());
        return waitingQueue.rank(scheduleId, userId).map(r -> r + 1).orElse(0L);
    }

    @Override
    public QueueStatus status(long scheduleId, String userId) {
        return admissionStore.findToken(scheduleId, userId)
                .map(token -> new QueueStatus(0, true, token))
                .orElseGet(() -> new QueueStatus(
                        waitingQueue.rank(scheduleId, userId).map(r -> r + 1).orElse(0L),
                        false, null));
    }
}
