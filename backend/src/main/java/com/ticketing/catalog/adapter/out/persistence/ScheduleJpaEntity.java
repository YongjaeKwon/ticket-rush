package com.ticketing.catalog.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "schedule")
public class ScheduleJpaEntity {

    @Id
    private Long id;
    private Long eventId;
    private LocalDateTime startsAt;

    protected ScheduleJpaEntity() {
    }

    public Long getId() {
        return id;
    }

    public Long getEventId() {
        return eventId;
    }

    public LocalDateTime getStartsAt() {
        return startsAt;
    }
}
