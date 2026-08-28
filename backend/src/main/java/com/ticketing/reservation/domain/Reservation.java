package com.ticketing.reservation.domain;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 예매 애그리거트. 순수 자바 — 스프링·JPA import 금지.
 * 상태 전이 규칙은 전부 이 클래스의 메서드 안에만 있다.
 * 시각은 UTC 기준 LocalDateTime이며, 항상 호출자가 now를 주입한다(테스트 가능성).
 *
 * 주의: "같은 (회차, 좌석)에 살아있는 확정은 하나"는 여기가 아니라
 * DB confirmed_seat PK가 보장한다 — 도메인은 한 건의 예매만 안다.
 */
public class Reservation {

    /** 저장 전에는 null, 저장 후 어댑터가 reconstitute로 채운다. */
    private final Long id;
    private final long scheduleId;
    private final long seatId;
    private final String userId;
    private ReservationStatus status;
    private final LocalDateTime expiresAt;
    private final Long version;
    private final LocalDateTime createdAt;

    private Reservation(Long id, long scheduleId, long seatId, String userId,
                        ReservationStatus status, LocalDateTime expiresAt,
                        Long version, LocalDateTime createdAt) {
        this.id = id;
        this.scheduleId = scheduleId;
        this.seatId = seatId;
        this.userId = Objects.requireNonNull(userId, "userId");
        this.status = Objects.requireNonNull(status, "status");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    /** 좌석 홀드 성공 시점의 새 예매. 만료 시각 = now + holdFor(기본 5분). */
    public static Reservation hold(long scheduleId, long seatId, String userId,
                                   LocalDateTime now, Duration holdFor) {
        return new Reservation(null, scheduleId, seatId, userId,
                ReservationStatus.HELD, now.plus(holdFor), null, now);
    }

    /** 영속 어댑터 전용 — DB 행을 도메인 객체로 복원한다. */
    public static Reservation reconstitute(Long id, long scheduleId, long seatId, String userId,
                                           ReservationStatus status, LocalDateTime expiresAt,
                                           Long version, LocalDateTime createdAt) {
        return new Reservation(id, scheduleId, seatId, userId, status, expiresAt, version, createdAt);
    }

    /**
     * 결제 승인 → 확정. 홀드가 이미 만료됐으면 거부한다
     * (Redis 홀드가 먼저 사라져 다른 사람이 좌석을 잡았을 수 있다).
     */
    public void confirm(LocalDateTime now) {
        requireHeld("확정");
        if (now.isAfter(expiresAt)) {
            throw new ReservationException("HOLD_EXPIRED",
                    "홀드가 만료된 예매는 확정할 수 없습니다: " + expiresAt);
        }
        this.status = ReservationStatus.CONFIRMED;
    }

    /** 만료 스케줄러·결제 타임아웃 되돌리기(3단계)의 전이. */
    public void expire() {
        requireHeld("만료");
        this.status = ReservationStatus.EXPIRED;
    }

    /** 사용자 취소. 확정 후 취소(환불)는 범위 밖 — backlog. */
    public void cancel() {
        requireHeld("취소");
        this.status = ReservationStatus.CANCELLED;
    }

    private void requireHeld(String action) {
        if (status != ReservationStatus.HELD) {
            throw new ReservationException("INVALID_RESERVATION_STATE",
                    status + " 상태의 예매는 " + action + "할 수 없습니다");
        }
    }

    public boolean isHeld() {
        return status == ReservationStatus.HELD;
    }

    public Long id() {
        return id;
    }

    public long scheduleId() {
        return scheduleId;
    }

    public long seatId() {
        return seatId;
    }

    public String userId() {
        return userId;
    }

    public ReservationStatus status() {
        return status;
    }

    public LocalDateTime expiresAt() {
        return expiresAt;
    }

    public Long version() {
        return version;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }
}
