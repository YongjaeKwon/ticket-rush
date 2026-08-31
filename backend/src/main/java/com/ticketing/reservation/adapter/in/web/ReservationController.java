package com.ticketing.reservation.adapter.in.web;

import com.ticketing.reservation.application.port.in.CancelReservationUseCase;
import com.ticketing.reservation.application.port.in.CancelReservationUseCase.CancelCommand;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase;
import com.ticketing.reservation.application.port.in.ConfirmReservationUseCase.ConfirmCommand;
import com.ticketing.reservation.application.port.in.GetReservationUseCase;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase;
import com.ticketing.reservation.application.port.in.HoldSeatUseCase.HoldSeatCommand;
import com.ticketing.reservation.domain.Reservation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDateTime;

/**
 * 예매 REST 어댑터 (API 초안 5절).
 * 인증은 X-User-Id 헤더(범위 밖 단순화) — 입장 JWT 검증은 queue 모듈에서 붙인다.
 * 홀드·확정 POST에는 IdempotencyFilter가 Idempotency-Key를 강제한다.
 */
@RestController
@RequestMapping("/api/reservations")
class ReservationController {

    private final HoldSeatUseCase holdSeat;
    private final ConfirmReservationUseCase confirmReservation;
    private final CancelReservationUseCase cancelReservation;
    private final GetReservationUseCase getReservation;

    ReservationController(HoldSeatUseCase holdSeat, ConfirmReservationUseCase confirmReservation,
                          CancelReservationUseCase cancelReservation, GetReservationUseCase getReservation) {
        this.holdSeat = holdSeat;
        this.confirmReservation = confirmReservation;
        this.cancelReservation = cancelReservation;
        this.getReservation = getReservation;
    }

    record HoldRequest(@NotNull Long scheduleId, @NotNull Long seatId) {
    }

    record HoldResponse(long reservationId, LocalDateTime expiresAt) {
    }

    record ConfirmResponse(long reservationId, String status, String paymentTransactionId) {
    }

    record ReservationResponse(long reservationId, long scheduleId, long seatId,
                               String status, LocalDateTime expiresAt) {
    }

    @PostMapping
    ResponseEntity<HoldResponse> hold(@RequestHeader("X-User-Id") String userId,
                                      @RequestHeader(value = "Authorization", required = false) String authorization,
                                      @Valid @RequestBody HoldRequest request) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()) : null;
        var result = holdSeat.hold(
                new HoldSeatCommand(request.scheduleId(), request.seatId(), userId, token));
        return ResponseEntity.created(URI.create("/api/reservations/" + result.reservationId()))
                .body(new HoldResponse(result.reservationId(), result.expiresAt()));
    }

    @PostMapping("/{reservationId}/confirm")
    ConfirmResponse confirm(@RequestHeader("X-User-Id") String userId,
                            @PathVariable long reservationId) {
        var result = confirmReservation.confirm(new ConfirmCommand(reservationId, userId));
        return new ConfirmResponse(result.reservationId(), result.status().name(),
                result.paymentTransactionId());
    }

    @DeleteMapping("/{reservationId}")
    ResponseEntity<Void> cancel(@RequestHeader("X-User-Id") String userId,
                                @PathVariable long reservationId) {
        cancelReservation.cancel(new CancelCommand(reservationId, userId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{reservationId}")
    ReservationResponse get(@RequestHeader("X-User-Id") String userId,
                            @PathVariable long reservationId) {
        Reservation reservation = getReservation.get(reservationId, userId);
        return new ReservationResponse(reservation.id(), reservation.scheduleId(), reservation.seatId(),
                reservation.status().name(), reservation.expiresAt());
    }
}
