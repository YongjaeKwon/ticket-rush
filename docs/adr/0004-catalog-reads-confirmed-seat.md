# 0004. 좌석 상태 비트맵 — catalog가 confirmed_seat를 직접 읽는다 (1단계)
날짜: 2026-08-28 · 단계: 1 · 상태: 결정 · [English](0004-catalog-reads-confirmed-seat.en.md)

## 문맥
좌석 상태 API는 catalog 소관인데, 확정 사실(confirmed_seat)은 reservation의 데이터다. 아직 reservation 모듈이 없다.

## 선택지
- **catalog가 읽기 전용 네이티브 쿼리로 직접 조회**: JPA 엔티티 없이 JdbcClient SELECT 한 줄. 단순, 모듈 간 코드 의존 없음. 단점: DB 스키마 수준의 암묵 결합.
- reservation에 조회 포트를 만들고 catalog가 호출: 경계는 깨끗하나 존재하지 않는 모듈을 조회 때문에 먼저 만들게 됨. 순서 역전.

## 결정
직접 조회. 코드 의존이 아니라 데이터 의존이고, 방향도 "확정 사실을 읽는" 쪽이라 안전하다. JPA 매핑은 만들지 않는다 — 이 테이블의 매핑 주인은 reservation이다.

## 결과
얻는 것: catalog를 얇게 유지, 순서대로 진행. 잃는 것: 스키마 결합 → ConfirmedSeatReader 포트 뒤에 숨겨 교체 지점을 한 곳으로 좁힘. 다시 볼 조건: 3단계 서비스 분리 시 reservation의 상태 이벤트 프로젝션(또는 API)으로 교체.
