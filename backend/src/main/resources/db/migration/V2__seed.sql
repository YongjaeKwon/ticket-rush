-- V2__seed.sql — 시드: 공연 1, 회차 1, 구역 4(A~D) × 500석 = 2,000석
-- 좌석은 구역당 20행 × 25열.

-- 시각은 전부 UTC로 저장한다. 화면이 +9h(KST)로 바꿔 보여준다.
INSERT INTO event (id, title, venue, open_at)
VALUES (1, '2026 TICKET RUSH LIVE', '올림픽공원 체조경기장', '2026-09-07 02:00:00');   -- KST 11:00

INSERT INTO schedule (id, event_id, starts_at)
VALUES (1, 1, '2026-10-17 10:00:00');   -- KST 19:00

INSERT INTO section (id, schedule_id, name, seat_count)
VALUES (1, 1, 'A', 500),
       (2, 1, 'B', 500),
       (3, 1, 'C', 500),
       (4, 1, 'D', 500);

-- 구역당 0~499 번호를 20행 × 25열로 전개
INSERT INTO seat (section_id, row_no, col_no)
WITH RECURSIVE nums AS (
    SELECT 0 AS n
    UNION ALL
    SELECT n + 1 FROM nums WHERE n < 499
)
SELECT sec.id, (nums.n DIV 25) + 1, (nums.n MOD 25) + 1
FROM section sec
         CROSS JOIN nums
WHERE sec.schedule_id = 1
ORDER BY sec.id, nums.n;
