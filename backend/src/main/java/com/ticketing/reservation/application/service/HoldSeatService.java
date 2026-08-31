package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.HoldSeatUseCase;
import com.ticketing.reservation.application.port.out.AdmissionTokenVerifier;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.application.port.out.SeatHoldStore;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationHeld;
import com.ticketing.shared.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 좌석 홀드 유스케이스 (ARCHITECTURE 4-3 "좌석 홀드" 순서 그대로).
 *
 * 1. Redis 선점 (SET NX EX) — 경합의 1차 필터. 실패면 SEAT_ALREADY_HELD.
 * 2. 한 트랜잭션으로 DB에 HELD 저장 + Outbox 행 기록.
 * 3. DB가 실패하면 Redis 선점을 즉시 되돌린다.
 *
 * 커밋 자체가 실패하는 극단 케이스는 되돌리기가 실행되지 않지만,
 * 그때 좌석은 TTL 5분이 지나면 자연 해제된다 — DB엔 아무것도 없으므로 정합성은 유지된다.
 */
@Service
public class HoldSeatService implements HoldSeatUseCase {

    static final Duration HOLD_TTL = Duration.ofMinutes(5);

    private final SeatHoldStore seatHoldStore;
    private final ReservationRepository reservationRepository;
    private final EventPublisher eventPublisher;
    private final AdmissionTokenVerifier admissionVerifier;
    private final Clock clock;

    public HoldSeatService(SeatHoldStore seatHoldStore, ReservationRepository reservationRepository,
                           EventPublisher eventPublisher, AdmissionTokenVerifier admissionVerifier,
                           Clock clock) {
        this.seatHoldStore = seatHoldStore;
        this.reservationRepository = reservationRepository;
        this.eventPublisher = eventPublisher;
        this.admissionVerifier = admissionVerifier;
        this.clock = clock;
    }

    @Override
    @Transactional
    public HoldResult hold(HoldSeatCommand command) {
        requireAdmission(command);
        if (!seatHoldStore.tryHold(command.scheduleId(), command.seatId(), command.userId(), HOLD_TTL)) {
            throw ApiException.conflict("SEAT_ALREADY_HELD", "이미 선점된 좌석입니다");
        }
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            Reservation saved = reservationRepository.save(
                    Reservation.hold(command.scheduleId(), command.seatId(), command.userId(), now, HOLD_TTL));
            eventPublisher.publish(ReservationHeld.from(saved, now));
            return new HoldResult(saved.id(), saved.expiresAt());
        } catch (RuntimeException e) {
            // 예외는 그대로 전파돼 트랜잭션이 롤백된다. Redis 선점만 여기서 되돌린다.
            seatHoldStore.release(command.scheduleId(), command.seatId());
            throw e;
        }
    }

    /** 대기열을 거친 사용자만 홀드할 수 있다 — 서명·만료·회차·본인 일치를 확인한다. */
    private void requireAdmission(HoldSeatCommand command) {
        var claims = admissionVerifier.verify(command.admissionToken())
                .orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "ADMISSION_REQUIRED", "대기열 입장 후에 좌석을 선점할 수 있습니다"));
        if (claims.scheduleId() != command.scheduleId() || !claims.userId().equals(command.userId())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "ADMISSION_REQUIRED", "입장권이 이 회차·사용자의 것이 아닙니다");
        }
    }
}
