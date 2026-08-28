# 0003. 경합 테이블(reservation, confirmed_seat)에는 FK를 걸지 않는다
날짜: 2026-08-28 · 단계: 1 · 상태: 결정

## 문맥
V1__init.sql 설계 중. catalog 4테이블은 FK로 묶었는데, 홀드·확정이 몰리는 reservation/confirmed_seat에도 schedule·seat FK를 걸지 결정 필요.

## 선택지
- **FK 없음 + 인덱스만**: 쓰기 핫패스에서 부모 행 공유 락·검증 비용 제거. catalog는 읽기 전용 시드라 참조 무결성이 깨질 경로가 사실상 없음.
- FK 있음: DB가 무결성 보장. 단점: 대량 동시 INSERT 시 부모 키 락 경합, 4단계 부하 수치에 잡음.

## 결정
FK 없음. 존재하지 않는 seat_id는 애플리케이션(좌석 조회) 단계에서 걸러지고, 핵심 불변식(이중 확정 금지)은 FK가 아니라 confirmed_seat PK(schedule_id, seat_id)가 지킨다.

## 결과
얻는 것: 홀드 INSERT 경로 단순·빠름. 잃는 것: DB 수준 참조 무결성 → 시드 이후 catalog 불변이라는 전제에 의존. 다시 볼 조건: catalog가 쓰기 가능해지면.
