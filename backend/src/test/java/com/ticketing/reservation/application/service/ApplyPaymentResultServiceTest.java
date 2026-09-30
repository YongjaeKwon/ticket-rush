package com.ticketing.reservation.application.service;

import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResult;
import com.ticketing.reservation.application.port.in.ApplyPaymentResultUseCase.PaymentResultCommand;
import com.ticketing.reservation.application.port.out.EventPublisher;
import com.ticketing.reservation.application.port.out.ProcessedEventStore;
import com.ticketing.reservation.application.port.out.ReservationRepository;
import com.ticketing.reservation.application.port.out.SeatHoldStore;
import com.ticketing.reservation.domain.PaymentStatus;
import com.ticketing.reservation.domain.Reservation;
import com.ticketing.reservation.domain.ReservationStatus;
import com.ticketing.shared.event.DomainEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 결과 3종의 반영과 멱등을 — 스프링 없이 가짜 포트로 검증한다.
 * (장부의 진짜 트랜잭션 의미는 공용 장부를 쓰는 PaymentConcurrencyTest가 실물로 증명)
 */
class ApplyPaymentResultServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-29T05:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED);

    private Reservation stored;
    private Reservation savedResult;
    private boolean seatTaken;                     // registerConfirmedSeat가 false를 돌려줄지
    private boolean raceLost;                      // markProcessed가 false를 돌려줄지(경쟁 패배)
    private final List<Long> confirmedSeats = new ArrayList<>();
    private final List<DomainEvent> published = new ArrayList<>();
    private final List<String> releasedHolds = new ArrayList<>();
    private final Set<String> ledger = new HashSet<>();

    private ApplyPaymentResultService service() {
        ProcessedEventStore processed = new ProcessedEventStore() {
            @Override public boolean alreadyProcessed(String consumer, String eventId) {
                return ledger.contains(consumer + ":" + eventId);
            }
            @Override public boolean markProcessed(String consumer, String eventId) {
                return !raceLost && ledger.add(consumer + ":" + eventId);
            }
        };
        ReservationRepository repo = new ReservationRepository() {
            @Override public Reservation save(Reservation reservation) {
                savedResult = reservation;
                return reservation;
            }
            @Override public Optional<Reservation> findById(long reservationId) {
                return Optional.ofNullable(stored != null && stored.id() == reservationId ? stored : null);
            }
            @Override public List<Reservation> findExpiredHeld(LocalDateTime now) { return List.of(); }
            @Override public boolean registerConfirmedSeat(long scheduleId, long seatId, long reservationId) {
                if (seatTaken) return false;
                confirmedSeats.add(seatId);
                return true;
            }
        };
        SeatHoldStore holds = new SeatHoldStore() {
            @Override public boolean tryHold(long s, long seat, String u, Duration ttl) { return true; }
            @Override public void release(long scheduleId, long seatId, String userId) {
                releasedHolds.add(scheduleId + ":" + seatId + ":" + userId);
            }
        };
        EventPublisher events = published::add;
        TransactionTemplate tx = new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        return new ApplyPaymentResultService(repo, processed, holds, events, tx, FIXED);
    }

    private static Reservation held(long id, LocalDateTime expiresAt) {
        // 결제 결과는 확정 요청(REQUESTED) 뒤에만 온다
        return Reservation.reconstitute(id, 1L, 17L, "u-1", ReservationStatus.HELD, expiresAt, 0L, NOW,
                PaymentStatus.REQUESTED, null);
    }

    private static PaymentResultCommand command(PaymentResult result) {
        return new PaymentResultCommand("evt-1", 501L, result,
                result == PaymentResult.APPROVED ? "mock-tx-501" : null);
    }

    @Test
    void 승인이면_확정_좌석기록_이벤트_홀드해제까지() {
        stored = held(501L, NOW.plusMinutes(3));
        service().apply(command(PaymentResult.APPROVED));

        assertThat(savedResult.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(savedResult.paymentStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(savedResult.paymentTransactionId()).isEqualTo("mock-tx-501"); // 완료 화면의 승인번호
        assertThat(confirmedSeats).containsExactly(17L);
        assertThat(published).singleElement()
                .satisfies(e -> assertThat(e.eventType()).isEqualTo("ReservationConfirmed"));
        assertThat(releasedHolds).containsExactly("1:17:u-1");
    }

    @Test
    void 홀드가_만료된_승인은_예매를_닫고_승인_사실을_남긴다() {
        stored = held(501L, NOW.minusSeconds(1));
        service().apply(command(PaymentResult.APPROVED));

        // REQUESTED로 두면 결론이 조회에 안 보이고 재결제·취소가 막힌다 — EXPIRED + APPROVED로 닫는다
        assertThat(savedResult.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(savedResult.paymentStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(savedResult.paymentTransactionId()).isEqualTo("mock-tx-501"); // 환불 보상의 근거
        assertThat(confirmedSeats).isEmpty();   // 만료 검사가 좌석 기록보다 먼저다
        assertThat(published).singleElement()
                .satisfies(e -> assertThat(e.eventType()).isEqualTo("ReservationExpired"));
        assertThat(ledger).hasSize(1); // 재전달돼도 같은 결과 — 다시 처리하지 않는다
    }

    @Test
    void 좌석을_이미_남이_확정했으면_확정하지_않고_예매를_닫는다_UNIQUE_방어() {
        stored = held(501L, NOW.plusMinutes(3));
        seatTaken = true;
        service().apply(command(PaymentResult.APPROVED));

        // 이중 예매는 UNIQUE가 막았다 — 이 예매는 결론(EXPIRED + APPROVED)으로 닫혀 조회에 드러난다
        assertThat(savedResult.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(savedResult.paymentStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(published).singleElement()
                .satisfies(e -> assertThat(e.eventType()).isEqualTo("ReservationExpired"));
        assertThat(releasedHolds).containsExactly("1:17:u-1");   // 제 홀드만 비교-삭제
        assertThat(ledger).hasSize(1);
    }

    @Test
    void 거절이면_HELD_유지에_DECLINED_표지를_남기고_홀드는_지킨다() {
        stored = held(501L, NOW.plusMinutes(3));
        service().apply(command(PaymentResult.DECLINED));

        // 표지가 있어야 조회가 "결제 중"과 "거절됨"을 구분한다 — 웹은 이걸 보고 재시도를 연다
        assertThat(savedResult.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(savedResult.paymentStatus()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(published).isEmpty();
        assertThat(releasedHolds).isEmpty();
        assertThat(ledger).hasSize(1);
    }

    @Test
    void 실패면_EXPIRED_되돌리기와_홀드해제() {
        stored = held(501L, NOW.plusMinutes(3));
        service().apply(command(PaymentResult.FAILED));

        assertThat(savedResult.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(savedResult.paymentStatus()).isEqualTo(PaymentStatus.FAILED); // 5분 만료와 구분
        assertThat(published).singleElement()
                .satisfies(e -> assertThat(e.eventType()).isEqualTo("ReservationExpired"));
        assertThat(releasedHolds).containsExactly("1:17:u-1");
    }

    @Test
    void 이미_확정된_예매의_실패_쪽지는_상태를_건드리지_않는다() {
        stored = Reservation.reconstitute(501L, 1L, 17L, "u-1",
                ReservationStatus.CONFIRMED, NOW.plusMinutes(3), 1L, NOW, PaymentStatus.APPROVED, "mock-tx");
        service().apply(command(PaymentResult.FAILED));

        assertThat(stored.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(savedResult).isNull();
        assertThat(published).isEmpty();
        assertThat(ledger).hasSize(1);
    }

    @Test
    void 예매가_없는_쪽지는_장부만_남긴다() {
        stored = null;
        service().apply(command(PaymentResult.APPROVED));

        assertThat(savedResult).isNull();
        assertThat(published).isEmpty();
        assertThat(ledger).hasSize(1);
    }

    @Test
    void 같은_쪽지_번호는_두_번_반영되지_않는다() {
        stored = held(501L, NOW.plusMinutes(3));
        ApplyPaymentResultService service = service();
        service.apply(command(PaymentResult.APPROVED));
        service.apply(command(PaymentResult.APPROVED));

        assertThat(confirmedSeats).hasSize(1);
        assertThat(published).hasSize(1);
        assertThat(releasedHolds).hasSize(1);
    }

    @Test
    void 버전_충돌은_삼키지_않고_전파한다_재전달이_수렴시킨다() {
        // 만료 스케줄러·취소와의 정상 경쟁 — ReservationException(기록하고 넘어감)과 달리 던져야 재전달된다
        stored = held(501L, NOW.plusMinutes(3));
        ApplyPaymentResultService service = new ApplyPaymentResultService(
                new ReservationRepository() {
                    @Override public Reservation save(Reservation r) {
                        throw new org.springframework.orm.ObjectOptimisticLockingFailureException("reservation", 501L);
                    }
                    @Override public java.util.Optional<Reservation> findById(long id) {
                        return java.util.Optional.of(stored);
                    }
                    @Override public List<Reservation> findExpiredHeld(LocalDateTime now) { return List.of(); }
                    @Override public boolean registerConfirmedSeat(long s, long seat, long r) { return true; }
                },
                new ProcessedEventStore() {
                    @Override public boolean alreadyProcessed(String c, String e) { return false; }
                    @Override public boolean markProcessed(String c, String e) { return true; }
                },
                new SeatHoldStore() {
                    @Override public boolean tryHold(long s, long seat, String u, Duration ttl) { return true; }
                    @Override public void release(long s, long seat, String u) { releasedHolds.add("released"); }
                },
                published::add,
                new TransactionTemplate() {
                    @Override public <T> T execute(TransactionCallback<T> action) {
                        return action.doInTransaction(new SimpleTransactionStatus());
                    }
                }, FIXED);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.apply(command(PaymentResult.APPROVED)))
                .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
        assertThat(releasedHolds).isEmpty(); // 커밋 못 했으니 홀드도 건드리지 않는다
    }

    @Test
    void 확인과_기록_사이_경쟁에서_지면_아무것도_반영되지_않는다() {
        stored = held(501L, NOW.plusMinutes(3));
        // 선확인은 통과했는데 장부에 적을 때 누가 먼저 적은 상황 — markProcessed만 false
        raceLost = true;
        service().apply(command(PaymentResult.APPROVED));

        assertThat(savedResult).isNull();
        assertThat(confirmedSeats).isEmpty();
        assertThat(published).isEmpty();
        assertThat(releasedHolds).isEmpty();
    }
}
