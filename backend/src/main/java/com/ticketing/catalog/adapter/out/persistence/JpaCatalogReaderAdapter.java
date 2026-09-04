package com.ticketing.catalog.adapter.out.persistence;

import com.ticketing.catalog.application.port.out.CatalogReader;
import com.ticketing.catalog.domain.EventDetail;
import com.ticketing.catalog.domain.EventSummary;
import com.ticketing.catalog.domain.ScheduleDetail;
import com.ticketing.catalog.domain.SeatLayout;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
class JpaCatalogReaderAdapter implements CatalogReader {

    private final EventJpaRepository events;
    private final ScheduleJpaRepository schedules;
    private final SectionJpaRepository sections;
    private final SeatJpaRepository seats;

    JpaCatalogReaderAdapter(EventJpaRepository events, ScheduleJpaRepository schedules,
                            SectionJpaRepository sections, SeatJpaRepository seats) {
        this.events = events;
        this.schedules = schedules;
        this.sections = sections;
        this.seats = seats;
    }

    @Override
    public List<EventSummary> findEvents() {
        return events.findAllByOrderByOpenAtAsc().stream()
                .map(e -> new EventSummary(e.getId(), e.getTitle(), e.getVenue(), e.getOpenAt()))
                .toList();
    }

    @Override
    public Optional<EventDetail> findEvent(long eventId) {
        return events.findById(eventId).map(e -> new EventDetail(
                e.getId(), e.getTitle(), e.getVenue(), e.getOpenAt(),
                schedules.findByEventIdOrderByStartsAtAsc(e.getId()).stream()
                        .map(s -> new EventDetail.ScheduleSummary(s.getId(), s.getStartsAt()))
                        .toList()));
    }

    @Override
    public Optional<ScheduleDetail> findSchedule(long scheduleId) {
        return schedules.findById(scheduleId).flatMap(s -> events.findById(s.getEventId())
                .map(e -> new ScheduleDetail(s.getId(), s.getStartsAt(),
                        e.getId(), e.getTitle(), e.getVenue())));
    }

    @Override
    public Optional<SeatLayout> findLayout(long scheduleId) {
        if (!schedules.existsById(scheduleId)) {
            return Optional.empty();
        }
        List<SectionJpaEntity> sectionRows = sections.findByScheduleIdOrderByIdAsc(scheduleId);
        List<Long> sectionIds = sectionRows.stream().map(SectionJpaEntity::getId).toList();
        // 구역별로 묶되 (행, 열) 정렬은 쿼리가 보장 — 이 순서가 비트맵 계약이다
        Map<Long, List<SeatLayout.SeatPosition>> seatsBySection = seats
                .findBySectionIdInOrderBySectionIdAscRowNoAscColNoAsc(sectionIds).stream()
                .collect(Collectors.groupingBy(
                        SeatJpaEntity::getSectionId,
                        Collectors.mapping(
                                s -> new SeatLayout.SeatPosition(s.getId(), s.getRowNo(), s.getColNo()),
                                Collectors.toList())));
        List<SeatLayout.SectionLayout> layoutSections = sectionRows.stream()
                .map(sec -> new SeatLayout.SectionLayout(
                        sec.getId(), sec.getName(), seatsBySection.getOrDefault(sec.getId(), List.of())))
                .toList();
        return Optional.of(new SeatLayout(scheduleId, layoutSections));
    }
}
