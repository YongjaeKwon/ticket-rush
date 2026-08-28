-- V3__modulith_event_publication.sql — Spring Modulith 이벤트 발행 로그 (ARCHITECTURE.md 4-5)
-- 공식 스키마 그대로: spring-modulith-events-jdbc 2.0.5 / schemas/v2/schema-mysql.sql
-- 자동 생성(spring.modulith.events.jdbc.schema-initialization)은 쓰지 않는다 — 테이블은 Flyway만 만든다.

CREATE TABLE IF NOT EXISTS EVENT_PUBLICATION
(
  ID                     VARCHAR(36) NOT NULL,
  LISTENER_ID            VARCHAR(512) NOT NULL,
  EVENT_TYPE             VARCHAR(512) NOT NULL,
  SERIALIZED_EVENT       VARCHAR(4000) NOT NULL,
  PUBLICATION_DATE       TIMESTAMP(6) NOT NULL,
  COMPLETION_DATE        TIMESTAMP(6) DEFAULT NULL NULL,
  STATUS                 VARCHAR(20),
  COMPLETION_ATTEMPTS    INT,
  LAST_RESUBMISSION_DATE TIMESTAMP(6) DEFAULT NULL NULL,
  PRIMARY KEY (ID),
  INDEX EVENT_PUBLICATION_BY_COMPLETION_DATE_IDX (COMPLETION_DATE)
);
