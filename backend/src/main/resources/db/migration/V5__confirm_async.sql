-- V5__confirm_async.sql — 3단계: 확정 API 비동기 전환 (ADR 0009)
-- 확정 요청은 202로 접수만 하고 결과는 결제 결과 쪽지가 예매에 남긴다.
-- 거절은 상태 전이가 아니라서(HELD 유지) "결제 중"과 "거절됨"을 구분할 칸이 따로 필요했다.

ALTER TABLE reservation
    ADD COLUMN payment_status VARCHAR(20)  NULL AFTER status,          -- REQUESTED / APPROVED / DECLINED / FAILED, 요청 전 NULL
    ADD COLUMN payment_tx_id  VARCHAR(200) NULL AFTER payment_status;  -- PG 승인번호, 승인 때만 (payment.pg_tx_id와 같은 폭)
