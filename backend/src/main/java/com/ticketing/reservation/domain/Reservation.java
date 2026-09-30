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
    /** 결제 진행 상태 — 요청 전에는 null (ADR 0009) */
    private PaymentStatus paymentStatus;
    /** PG 승인번호 — 승인 때만 */
    private String paymentTransactionId;

    private Reservation(Long id, long scheduleId, long seatId, String userId,
                        ReservationStatus status, LocalDateTime expiresAt,
                        Long version, LocalDateTime createdAt,
                        PaymentStatus paymentStatus, String paymentTransactionId) {
        this.id = id;
        this.scheduleId = scheduleId;
        this.seatId = seatId;
        this.userId = Objects.requireNonNull(userId, "userId");
        this.status = Objects.requireNonNull(status, "status");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.paymentStatus = paymentStatus;
        this.paymentTransactionId = paymentTransactionId;
    }

    /** 좌석 홀드 성공 시점의 새 예매. 만료 시각 = now + holdFor(기본 5분). */
    public static Reservation hold(long scheduleId, long seatId, String userId,
                                   LocalDateTime now, Duration holdFor) {
        return new Reservation(null, scheduleId, seatId, userId,
                ReservationStatus.HELD, now.plus(holdFor), null, now, null, null);
    }

    /** 영속 어댑터 전용 — DB 행을 도메인 객체로 복원한다. */
    public static Reservation reconstitute(Long id, long scheduleId, long seatId, String userId,
                                           ReservationStatus status, LocalDateTime expiresAt,
                                           Long version, LocalDateTime createdAt,
                                           PaymentStatus paymentStatus, String paymentTransactionId) {
        return new Reservation(id, scheduleId, seatId, userId, status, expiresAt, version, createdAt,
                paymentStatus, paymentTransactionId);
    }

    /**
     * 확정 요청 — 결제를 요청하고 결과를 기다린다 (3단계 비동기 확정, ADR 0009).
     * 진행 중인 시도가 있으면 거부한다: 결과가 오기 전에 두 번째 요청을 받으면
     * 결제 요청 쪽지가 두 장 나가 돈이 두 번 승인된다.
     */
    public void requestPayment(LocalDateTime now) {
        requireHeld("결제 요청");
        requireNotExpired(now, "결제를 요청할");
        if (paymentStatus == PaymentStatus.REQUESTED) {
            throw new ReservationException("PAYMENT_IN_PROGRESS",
                    "결제가 진행 중입니다. 결과를 기다려 주세요");
        }
        this.paymentStatus = PaymentStatus.REQUESTED;
    }

    /**
     * 결제 승인 → 확정. 홀드가 이미 만료됐으면 거부한다
     * (Redis 홀드가 먼저 사라져 다른 사람이 좌석을 잡았을 수 있다).
     */
    public void confirm(LocalDateTime now, String paymentTransactionId) {
        requireHeld("확정");
        requireNotExpired(now, "확정할");
        this.status = ReservationStatus.CONFIRMED;
        this.paymentStatus = PaymentStatus.APPROVED;
        this.paymentTransactionId = Objects.requireNonNull(paymentTransactionId, "paymentTransactionId");
    }

    /**
     * 승인됐지만 좌석을 확정하지 못했다 — 홀드가 이미 만료됐거나, 같은 좌석을 남이 먼저 확정했다(UNIQUE).
     * 예매는 끝내고(EXPIRED) 승인 사실과 승인번호는 남긴다: REQUESTED로 두면 결론이 조회에 드러나지 않고
     * 재결제·취소가 모두 막힌 채 남는다. 환불 보상은 backlog.
     */
    public void loseSeat(String paymentTransactionId) {
        requireHeld("좌석 충돌 반영");
        this.status = ReservationStatus.EXPIRED;
        this.paymentStatus = PaymentStatus.APPROVED;
        this.paymentTransactionId = Objects.requireNonNull(paymentTransactionId, "paymentTransactionId");
    }

    /** 결제 거절 — 전이가 아니다. HELD 그대로, 남은 시간 안에 새 시도를 받는다. */
    public void declinePayment() {
        requireHeld("결제 거절 반영");
        this.paymentStatus = PaymentStatus.DECLINED;
    }

    /** 결제 실패(시스템 오류) — 되돌리기: 좌석을 풀어준다. 거절과 달리 재시도 대상이 아니다. */
    public void failPayment() {
        requireHeld("결제 실패 반영");
        this.status = ReservationStatus.EXPIRED;
        this.paymentStatus = PaymentStatus.FAILED;
    }

    /** 만료 스케줄러·결제 타임아웃 되돌리기(3단계)의 전이. */
    public void expire() {
        requireHeld("만료");
        this.status = ReservationStatus.EXPIRED;
    }

    /**
     * 사용자 취소. 확정 후 취소(환불)는 범위 밖 — backlog.
     * 결제 진행 중에는 막는다 — 취소 뒤에 승인이 도착하면 돈은 나갔는데 좌석이 없게 된다.
     */
    public void cancel() {
        requireHeld("취소");
        if (paymentStatus == PaymentStatus.REQUESTED) {
            throw new ReservationException("PAYMENT_IN_PROGRESS",
                    "결제가 진행 중이라 취소할 수 없습니다. 결과를 기다려 주세요");
        }
        this.status = ReservationStatus.CANCELLED;
    }

    private void requireNotExpired(LocalDateTime now, String action) {
        if (isExpiredAt(now)) {
            throw new ReservationException("HOLD_EXPIRED",
                    "홀드가 만료된 예매는 " + action + " 수 없습니다: " + expiresAt);
        }
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

    /** 홀드 만료 시각이 지났는가 — confirm()과 같은 기준(정각까지는 유효) */
    public boolean isExpiredAt(LocalDateTime now) {
        return now.isAfter(expiresAt);
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

    public PaymentStatus paymentStatus() {
        return paymentStatus;
    }

    public String paymentTransactionId() {
        return paymentTransactionId;
    }
}
