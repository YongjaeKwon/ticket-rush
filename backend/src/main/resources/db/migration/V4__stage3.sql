-- V4__stage3.sql — 3단계: 결제 기록 + 멱등 컨슈머 장부 (ARCHITECTURE.md 4-4)
-- 시각은 전부 DATETIME(6) UTC.

-- ── payment ──────────────────────────────────────────────
-- 결제 시도 하나 = 한 행. payment 모듈 소유 — reservation은 이벤트로만 결과를 안다.
-- reservation_id에 FK를 걸지 않는다: 참조 대상이 경합 테이블이라 잠금 전파를 피한다 (ADR 0003과 같은 이유).

CREATE TABLE payment (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT       NOT NULL,
    amount         INT          NOT NULL,              -- KRW. 가격 모델이 없어 요청 시점 금액을 그대로 기록
    status         VARCHAR(20)  NOT NULL,              -- APPROVED / DECLINED / FAILED
    pg_tx_id       VARCHAR(200) NULL,                  -- PG 승인번호, 승인 때만. 실 PG(토스 paymentKey 최대 200자) 대비
    created_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_payment_reservation (reservation_id)     -- 예매 하나의 시도 이력 조회용
) ENGINE = InnoDB;

-- ── processed_event ──────────────────────────────────────
-- 멱등 컨슈머 장부. 처리 전에 (consumer, event_id)를 insert하고 —
-- 중복이면 건너뛴다 — 실제 처리와 같은 트랜잭션으로 커밋한다 (ARCHITECTURE.md 4-5).

CREATE TABLE processed_event (
    consumer     VARCHAR(100) NOT NULL,                -- 컨슈머 이름 (예: reservation.payment-result)
    event_id     VARCHAR(36)  NOT NULL,                -- 이벤트 봉투의 eventId (UUID)
    processed_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (consumer, event_id)
) ENGINE = InnoDB;
