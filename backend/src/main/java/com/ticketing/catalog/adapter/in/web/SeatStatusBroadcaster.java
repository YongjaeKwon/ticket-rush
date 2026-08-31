package com.ticketing.catalog.adapter.in.web;

import com.ticketing.catalog.application.port.in.GetSeatLayoutUseCase;
import com.ticketing.catalog.application.port.in.GetSeatStatusUseCase;
import com.ticketing.catalog.domain.SeatLayout;
import com.ticketing.catalog.domain.SeatStatusBitmap;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 좌석 상태 SSE 팬아웃 (ARCHITECTURE 8-4).
 * 회차마다 폴러 하나가 500ms 간격으로 비트맵을 읽어, 이전과 달라진 좌석만
 * 구독자 전원에게 보낸다 — 클라이언트가 몇 명이든 DB/Redis 조회는 회차당 1회다.
 * 다중 인스턴스 팬아웃(Redis Pub/Sub)은 3단계에서.
 */
@Component
class SeatStatusBroadcaster {

    private final GetSeatStatusUseCase getSeatStatus;
    private final GetSeatLayoutUseCase getSeatLayout;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "seat-sse");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<Long, ScheduleFeed> feeds = new ConcurrentHashMap<>();

    SeatStatusBroadcaster(GetSeatStatusUseCase getSeatStatus, GetSeatLayoutUseCase getSeatLayout) {
        this.getSeatStatus = getSeatStatus;
        this.getSeatLayout = getSeatLayout;
    }

    record SeatChange(long seatId, int status) {
    }

    record SnapshotSection(long sectionId, int seatCount, String bitmap) {
    }

    /** 구독 — 접속 즉시 전체 스냅샷을 보내고, 이후엔 변경분만 받는다. */
    SseEmitter subscribe(long scheduleId) {
        SseEmitter emitter = new SseEmitter(TimeUnit.MINUTES.toMillis(10));
        ScheduleFeed feed = feeds.computeIfAbsent(scheduleId, ScheduleFeed::new);
        feed.add(emitter);
        return emitter;
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private class ScheduleFeed {

        private final long scheduleId;
        private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        private final AtomicLong sequence = new AtomicLong();
        private final ScheduledFuture<?> task;
        /** 구역별 좌석 id 배열 — 배치 순서(비트맵 인덱스)와 1:1. 배치는 불변이라 한 번만 읽는다 */
        private final Map<Long, long[]> seatIdsBySection;
        private volatile SeatStatusBitmap previous;

        ScheduleFeed(long scheduleId) {
            this.scheduleId = scheduleId;
            SeatLayout layout = getSeatLayout.getLayout(scheduleId);
            this.seatIdsBySection = new ConcurrentHashMap<>();
            for (SeatLayout.SectionLayout section : layout.sections()) {
                seatIdsBySection.put(section.id(),
                        section.seats().stream().mapToLong(SeatLayout.SeatPosition::id).toArray());
            }
            this.previous = getSeatStatus.getStatus(scheduleId);
            this.task = scheduler.scheduleAtFixedRate(this::tick, 500, 500, TimeUnit.MILLISECONDS);
        }

        void add(SseEmitter emitter) {
            emitter.onCompletion(() -> remove(emitter));
            emitter.onTimeout(() -> remove(emitter));
            emitter.onError(e -> remove(emitter));
            try {
                emitter.send(SseEmitter.event().name("seat-snapshot").data(snapshotOf(previous)));
                emitters.add(emitter);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }

        private void remove(SseEmitter emitter) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                task.cancel(false);
                feeds.remove(scheduleId, this);
            }
        }

        private void tick() {
            if (emitters.isEmpty()) {
                return;
            }
            SeatStatusBitmap current = getSeatStatus.getStatus(scheduleId);
            List<SeatChange> changes = diff(previous, current, seatIdsBySection);
            previous = current;
            if (changes.isEmpty()) {
                return;
            }
            var payload = Map.of("scheduleId", scheduleId, "changes", changes);
            String id = String.valueOf(sequence.incrementAndGet());
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("seat-status").id(id).data(payload));
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }
        }
    }

    private List<SnapshotSection> snapshotOf(SeatStatusBitmap bitmap) {
        return bitmap.sections().stream()
                .map(s -> new SnapshotSection(s.sectionId(), s.seatCount(),
                        Base64.getEncoder().encodeToString(s.bitmap())))
                .toList();
    }

    /** 두 비트맵을 좌석 단위로 비교해 달라진 좌석만 모은다. */
    private static List<SeatChange> diff(SeatStatusBitmap before, SeatStatusBitmap after,
                                         Map<Long, long[]> seatIdsBySection) {
        List<SeatChange> changes = new ArrayList<>();
        for (int s = 0; s < after.sections().size(); s++) {
            byte[] prev = before.sections().get(s).bitmap();
            byte[] next = after.sections().get(s).bitmap();
            int seatCount = after.sections().get(s).seatCount();
            long[] seatIds = seatIdsBySection.get(after.sections().get(s).sectionId());
            for (int i = 0; i < seatCount; i++) {
                int prevStatus = SeatStatusBitmap.statusAt(prev, i);
                int nextStatus = SeatStatusBitmap.statusAt(next, i);
                if (prevStatus != nextStatus) {
                    changes.add(new SeatChange(seatIds[i], nextStatus));
                }
            }
        }
        return changes;
    }
}
