package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.GetReservationUseCase;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.shared.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReservationQueryService implements GetReservationUseCase {

    private final ReservationRepository reservationRepository;

    public ReservationQueryService(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    @Override
    public Reservation get(long reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND",
                        "예매가 없습니다: " + reservationId));
        if (!reservation.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "RESERVATION_NOT_OWNED",
                    "본인의 예매만 조회할 수 있습니다");
        }
        return reservation;
    }
}
