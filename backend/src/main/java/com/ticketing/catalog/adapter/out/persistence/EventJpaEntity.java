package com.ticketing.catalog.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/* 읽기 전용 — 쓰기는 Flyway 시드뿐이라 @GeneratedValue도 두지 않는다. 연관관계 대신 FK를 Long으로 든다. */
@Entity
@Table(name = "event")
public class EventJpaEntity {

    @Id
    private Long id;
    private String title;
    private String venue;
    private LocalDateTime openAt;

    protected EventJpaEntity() {
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getVenue() {
        return venue;
    }

    public LocalDateTime getOpenAt() {
        return openAt;
    }
}
