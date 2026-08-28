package com.ticketing.reservation.adapter.out.persistence;

import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

interface ReservationJpaRepository extends JpaRepository<ReservationJpaEntity, Long> {

    /* (status, expires_at) 인덱스를 타는 만료 스케줄러 조회 */
    List<ReservationJpaEntity> findTop100ByStatusAndExpiresAtLessThanOrderByExpiresAtAsc(
            ReservationStatus status, LocalDateTime now);
}

@Component
class JpaReservationAdapter implements ReservationRepository {

    private final ReservationJpaRepository repository;
    private final JdbcClient jdbc;

    JpaReservationAdapter(ReservationJpaRepository repository, JdbcClient jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    @Override
    public Reservation save(Reservation reservation) {
        return repository.save(ReservationJpaEntity.fromDomain(reservation)).toDomain();
    }

    @Override
    public Optional<Reservation> findById(long reservationId) {
        return repository.findById(reservationId).map(ReservationJpaEntity::toDomain);
    }

    @Override
    public List<Reservation> findExpiredHeld(LocalDateTime now) {
        return repository
                .findTop100ByStatusAndExpiresAtLessThanOrderByExpiresAtAsc(ReservationStatus.HELD, now)
                .stream().map(ReservationJpaEntity::toDomain).toList();
    }

    /**
     * confirmed_seat INSERT. (schedule_id, seat_id) PK 충돌 = 이미 확정된 좌석 → false.
     * 이 한 줄이 이중 예매의 최종 방어선이다 — Redis가 통째로 죽어도 여기서 막힌다.
     */
    @Override
    public boolean registerConfirmedSeat(long scheduleId, long seatId, long reservationId) {
        try {
            jdbc.sql("""
                            INSERT INTO confirmed_seat (schedule_id, seat_id, reservation_id, created_at)
                            VALUES (:scheduleId, :seatId, :reservationId, UTC_TIMESTAMP(6))
                            """)
                    .param("scheduleId", scheduleId)
                    .param("seatId", seatId)
                    .param("reservationId", reservationId)
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
