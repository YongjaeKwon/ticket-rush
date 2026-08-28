package com.ticketing.catalog.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/* 어댑터 내부 전용 Spring Data 리포지토리 묶음. */
interface EventJpaRepository extends JpaRepository<EventJpaEntity, Long> {

    List<EventJpaEntity> findAllByOrderByOpenAtAsc();
}

interface ScheduleJpaRepository extends JpaRepository<ScheduleJpaEntity, Long> {

    List<ScheduleJpaEntity> findByEventIdOrderByStartsAtAsc(Long eventId);
}

interface SectionJpaRepository extends JpaRepository<SectionJpaEntity, Long> {

    List<SectionJpaEntity> findByScheduleIdOrderByIdAsc(Long scheduleId);
}

interface SeatJpaRepository extends JpaRepository<SeatJpaEntity, Long> {

    List<SeatJpaEntity> findBySectionIdInOrderBySectionIdAscRowNoAscColNoAsc(List<Long> sectionIds);
}
