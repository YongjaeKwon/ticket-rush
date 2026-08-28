package com.ticketing.reservation.adapter.out.persistence;

import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

interface ReservationJpaRepository extends JpaRepository<ReservationJpaEntity, Long> {
}

@Component
class JpaReservationAdapter implements ReservationRepository {

    private final ReservationJpaRepository repository;

    JpaReservationAdapter(ReservationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Reservation save(Reservation reservation) {
        return repository.save(ReservationJpaEntity.fromDomain(reservation)).toDomain();
    }

    @Override
    public Optional<Reservation> findById(long reservationId) {
        return repository.findById(reservationId).map(ReservationJpaEntity::toDomain);
    }
}
