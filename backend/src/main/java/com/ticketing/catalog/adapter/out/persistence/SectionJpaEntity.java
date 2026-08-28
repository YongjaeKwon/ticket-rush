package com.ticketing.catalog.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "section")
public class SectionJpaEntity {

    @Id
    private Long id;
    private Long scheduleId;
    private String name;
    private Integer seatCount;

    protected SectionJpaEntity() {
    }

    public Long getId() {
        return id;
    }

    public Long getScheduleId() {
        return scheduleId;
    }

    public String getName() {
        return name;
    }

    public Integer getSeatCount() {
        return seatCount;
    }
}
