package com.ticketing.reservation.adapter.out.persistence;

import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * reservation 테이블 매핑. 도메인 객체와 다른 클래스다 —
 * DB 사정(@Version, @GeneratedValue)이 도메인 규칙에 스며들지 않게 여기서 끊는다.
 */
@Entity
@Table(name = "reservation")
public class ReservationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long scheduleId;
    private Long seatId;
    private String userId;
    @Enumerated(EnumType.STRING)
    private ReservationStatus status;
    private LocalDateTime expiresAt;
    @Version
    private Long version;
    private LocalDateTime createdAt;

    protected ReservationJpaEntity() {
    }

    static ReservationJpaEntity fromDomain(Reservation reservation) {
        ReservationJpaEntity entity = new ReservationJpaEntity();
        entity.id = reservation.id();
        entity.scheduleId = reservation.scheduleId();
        entity.seatId = reservation.seatId();
        entity.userId = reservation.userId();
        entity.status = reservation.status();
        entity.expiresAt = reservation.expiresAt();
        entity.version = reservation.version();
        entity.createdAt = reservation.createdAt();
        return entity;
    }

    Reservation toDomain() {
        return Reservation.reconstitute(id, scheduleId, seatId, userId, status, expiresAt, version, createdAt);
    }
}
