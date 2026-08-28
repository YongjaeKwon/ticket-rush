package com.ticketing.catalog.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "seat")
public class SeatJpaEntity {

    @Id
    private Long id;
    private Long sectionId;
    private Integer rowNo;
    private Integer colNo;

    protected SeatJpaEntity() {
    }

    public Long getId() {
        return id;
    }

    public Long getSectionId() {
        return sectionId;
    }

    public Integer getRowNo() {
        return rowNo;
    }

    public Integer getColNo() {
        return colNo;
    }
}
