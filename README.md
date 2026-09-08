# 티켓러시

**한국어** | [English](README.en.md)

[![Backend CI](https://github.com/YongjaeKwon/ticket-rush/actions/workflows/ci.yml/badge.svg)](https://github.com/YongjaeKwon/ticket-rush/actions/workflows/ci.yml)

여러 사용자가 같은 좌석을 동시에 선택하는 상황을 다룬 공연 예매 서비스입니다.
웹에서 공연 조회부터 대기열 입장, 좌석 선점, 모의 결제, 예매 완료까지 이어지는 흐름을 구현했습니다.

이 프로젝트에서는 **요청이 겹치거나 응답을 받지 못했을 때 예매 상태를 어떻게 일관되게 유지할지**에 관심을 두었습니다.
Redis와 DB에 맡길 책임을 나누고, 만료와 재시도 같은 상황에서 어떤 처리가 필요한지를 코드와 테스트로 구체화했습니다.

## 설계에서 내린 선택

### 임시 선점은 Redis에, 확정된 예매는 DB에

좌석을 선택한 사용자에게는 5분의 결제 시간을 줍니다. Redis에서 키가 없을 때만 선점에 성공하도록 해 같은 좌석에 대한 경쟁 요청을 걸러냅니다.
다만 선점 키가 사라질 수 있으므로, 최종 확정은 DB의 `(회차, 좌석)` 복합 기본키로 한 번 더 제한했습니다.

예매 상태 변경, 확정 좌석 저장, 이벤트 기록은 하나의 DB 트랜잭션으로 묶었습니다.
임시로 잡아 둔 좌석과 확정된 예매를 구분하고, 각각의 수명과 책임에 맞는 저장소를 사용한 선택입니다.

### 결제를 기다리는 시간과 DB를 변경하는 시간을 분리

결제 응답을 기다리는 동안 DB 커넥션을 계속 점유하지 않도록 결제 호출을 트랜잭션 밖에 두었습니다.
결제 이후에는 트랜잭션 안에서 예매 상태와 만료 시각을 다시 확인하고, 확정 정보가 커밋된 뒤 Redis 선점을 해제합니다.

이 구조에서는 결제 승인과 DB 저장이 각각 성공하는지 살펴야 합니다.
두 작업 사이에서 실패했을 때의 보상 처리는 아래의 후속 과제로 남겼습니다.

### 응답을 받지 못한 경우와 결제가 거절된 경우를 구분

사용자가 결제 결과를 받지 못했다고 해서 서버의 처리까지 실패한 것은 아닙니다.
그래서 화면은 예매 상태를 먼저 조회하고, 결과를 알 수 없는 시도의 키를 유지하도록 했습니다.
반면 결제가 거절된 뒤 다시 시도할 때는 새로운 키를 사용합니다.

이 판단을 화면 코드와 분리한 순수 함수로 작성하고, [결정 기록](docs/adr/0006-idempotency-key-per-attempt.md)에 선택 이유와 제약을 남겼습니다.

## 현재 구성

```mermaid
flowchart LR
  WEB["Next.js 웹"] -->|"HTTP / SSE"| APP["Spring Boot<br/>공연 조회 · 대기열 · 예매"]
  APP -->|"대기열 · 좌석 선점"| REDIS[(Redis)]
  APP -->|"예매 · 확정 좌석 · 이벤트 기록"| DB[(MySQL)]
  APP -->|"동기 호출"| PAYMENT["모의 결제"]
```

백엔드는 하나의 Spring Boot 애플리케이션 안에 공연 조회(`catalog`), 대기열(`queue`), 예매(`reservation`) 모듈을 두었습니다.
예매 규칙은 Spring·JPA에 의존하지 않는 Java 객체에 두고, DB와 Redis를 사용하는 코드는 별도로 분리했습니다.

[웹](apps/web/src/app/)에서는 Canvas로 좌석을 선택하고, SSE로 대기 순번과 좌석 상태의 변화를 받습니다.
남은 선점 시간을 보여 주고 결제 결과에 따라 다음 행동을 안내합니다. 좌석 상태 해석과 좌표 계산은 [공통 패키지](packages/seat-map-core/src/)로 분리했습니다.

**사용 기술:** Java 21 · Spring Boot 4 · MySQL 8.4 · Redis 7 · Next.js · TypeScript.
DB 변경은 Flyway로 관리하고, 테스트에는 JUnit·Testcontainers·Vitest를 사용합니다.

## 코드와 테스트에서 살펴볼 부분

**좌석을 선점한 요청이 여러 건이어도 최종 확정은 하나여야 합니다.**
[선점 처리](backend/src/main/java/com/ticketing/reservation/application/service/HoldSeatService.java)와 [확정 처리](backend/src/main/java/com/ticketing/reservation/application/service/ConfirmReservationService.java)를 나누어 읽을 수 있습니다.
[동시성 테스트](backend/src/test/java/com/ticketing/reservation/ReservationConcurrencyTest.java)는 같은 좌석에 100개 스레드가 선점을 시도했을 때 성공이 1건인지 확인합니다. 선점 키를 삭제해 중복 홀드 10건을 만든 뒤, 동시에 확정해도 확정 좌석과 `CONFIRMED` 예매가 각각 1건인지도 확인합니다.

**실패의 종류에 따라 예매 상태와 재시도 방식이 달라져야 합니다.**
[만료된 홀드의 결제 거절](backend/src/test/java/com/ticketing/reservation/ConfirmReservationIntegrationTest.java), [결제 거절 시 홀드 유지](backend/src/test/java/com/ticketing/reservation/PaymentDeclinedIntegrationTest.java), [같은 키로 재요청했을 때의 응답 재생](backend/src/test/java/com/ticketing/reservation/ReservationApiIntegrationTest.java)을 통합 테스트로 확인합니다.
[웹의 재시도 정책 테스트](apps/web/src/lib/confirm-policy.test.ts)는 응답 유형에 따라 시도 키를 유지할지 판단하는 규칙을 다룹니다.

**업무 규칙과 외부 기술의 경계가 코드에서도 유지되어야 합니다.**
[예매 도메인](backend/src/main/java/com/ticketing/reservation/domain/Reservation.java)에 상태 전이와 만료 규칙을 모았습니다.
[ArchUnit](backend/src/test/java/com/ticketing/ArchitectureTest.java)과 [Spring Modulith 검사](backend/src/test/java/com/ticketing/ModularityTest.java)는 계층 의존 방향과 모듈 경계를 확인합니다.

동시성 테스트는 Testcontainers의 MySQL·Redis를 사용해 Java 유스케이스를 직접 호출합니다.
Redis 서버를 중단시키는 대신 홀드 키를 삭제해 데이터 유실 상황을 재현합니다. 백엔드 [CI](.github/workflows/ci.yml)는 `main` push와 PR에서 전체 Gradle 테스트를 실행합니다.

## 실행

Java 21과 Docker가 필요합니다. 웹은 Node.js와 저장소의 `packageManager`에 지정된 pnpm을 사용합니다.
명령은 저장소 루트에서 시작하며, Windows에서는 `./gradlew` 대신 `.\gradlew.bat`를 사용합니다.

```bash
# 개발용 MySQL·Redis와 백엔드
docker compose --profile infra up -d
cd backend
./gradlew bootRun
```

```bash
# 저장소 루트의 새 터미널에서 웹 실행
pnpm install --frozen-lockfile
pnpm --filter @ticket-rush/web dev
```

웹은 [localhost:3000](http://localhost:3000), 공연 API는 [localhost:8080/api/events](http://localhost:8080/api/events)에서 확인할 수 있습니다.
결제는 mock 어댑터로 처리하며 사용자 식별에는 데모용 `X-User-Id`를 사용합니다.

<details>
<summary>테스트 실행 명령</summary>

```bash
# 저장소 루트에서 실행. Testcontainers가 별도 MySQL·Redis를 시작합니다.
cd backend
./gradlew test --tests '*ReservationConcurrencyTest'
./gradlew test
```

```bash
# 저장소 루트에서 실행
pnpm --filter @ticket-rush/seat-map-core test
pnpm --filter @ticket-rush/web test
```

</details>

## 다음에 다룰 문제

서버의 멱등 처리는 현재 저장된 응답을 재생하는 방식입니다.
같은 키로 동시에 들어오는 요청의 실행 제어와, 결제 승인 뒤 DB 저장에 실패했을 때의 보상 처리가 남아 있습니다.

HTTP 부하 테스트의 응답시간·처리량은 아직 측정하지 않았습니다.
실행 환경과 시나리오를 함께 기록해 측정하고, 웹의 전체 예매 흐름에 대한 E2E 테스트와 CI 검사도 추가할 계획입니다.

Outbox는 이벤트를 DB에 기록하는 단계까지 구현했습니다. Kafka를 통한 이벤트 전달, 서비스 분리, Expo 앱은 후속 계획이며, 세부 사항은 [백로그](docs/backlog.md)에 정리했습니다.

## 관련 문서

- [설계 문서](docs/ARCHITECTURE.md) — 현재 구조와 후속 단계 설계
- [결정 기록](docs/adr/) — 대안, 선택 이유, 받아들인 제약
- [화면 디자인](docs/design/design-foundation.md) — 디자인 토큰과 프로토타입
