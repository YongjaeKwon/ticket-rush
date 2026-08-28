-- V1__init.sql — 1단계 테이블 전체 (ARCHITECTURE.md 4-4)
-- catalog 4개 + reservation + confirmed_seat + outbox.
-- 시각은 전부 DATETIME(6) UTC. 애플리케이션은 테이블을 만들지 않는다 (ddl-auto: validate).

-- ── catalog ──────────────────────────────────────────────

CREATE TABLE event (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    title    VARCHAR(200) NOT NULL,
    venue    VARCHAR(200) NOT NULL,
    open_at  DATETIME(6)  NOT NULL,             -- 예매 오픈 시각
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE schedule (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    event_id  BIGINT      NOT NULL,
    starts_at DATETIME(6) NOT NULL,             -- 공연 시작 시각
    PRIMARY KEY (id),
    CONSTRAINT fk_schedule_event FOREIGN KEY (event_id) REFERENCES event (id)
) ENGINE = InnoDB;

CREATE TABLE section (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT      NOT NULL,
    name        VARCHAR(50) NOT NULL,
    seat_count  INT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_section_schedule FOREIGN KEY (schedule_id) REFERENCES schedule (id),
    CONSTRAINT uk_section_name UNIQUE (schedule_id, name)
) ENGINE = InnoDB;

CREATE TABLE seat (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    section_id BIGINT NOT NULL,
    row_no     INT    NOT NULL,
    col_no     INT    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_seat_section FOREIGN KEY (section_id) REFERENCES section (id),
    CONSTRAINT uk_seat_position UNIQUE (section_id, row_no, col_no)
) ENGINE = InnoDB;

-- ── reservation ──────────────────────────────────────────
-- 경합이 몰리는 테이블에는 FK를 걸지 않는다 (docs/adr/0003).
-- 참조 정합성은 catalog가 읽기 전용 시드라는 사실 + 애플리케이션 검증으로 충분.

CREATE TABLE reservation (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    schedule_id BIGINT      NOT NULL,
    seat_id     BIGINT      NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    status      VARCHAR(20) NOT NULL,           -- HELD / CONFIRMED / EXPIRED / CANCELLED
    expires_at  DATETIME(6) NOT NULL,
    version     BIGINT      NOT NULL,           -- 낙관적 락
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_reservation_status_expires (status, expires_at),   -- 만료 스케줄러 조회용
    INDEX idx_reservation_schedule_seat (schedule_id, seat_id)
) ENGINE = InnoDB;

-- 같은 (회차, 좌석)에 살아있는 확정은 하나 — 이 PK가 이중 예매의 최종 방어선.
CREATE TABLE confirmed_seat (
    schedule_id    BIGINT      NOT NULL,
    seat_id        BIGINT      NOT NULL,
    reservation_id BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (schedule_id, seat_id),
    CONSTRAINT uk_confirmed_seat_reservation UNIQUE (reservation_id)
) ENGINE = InnoDB;

-- ── outbox ───────────────────────────────────────────────

CREATE TABLE outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        JSON         NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    published_at   DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_unpublished (published_at, created_at)      -- 릴레이 폴링용
) ENGINE = InnoDB;
