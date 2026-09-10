# CLAUDE.md — 선착순 티켓팅 (ticketing)

이 파일은 에이전트(Claude Code, Codex)가 **매 세션 읽는 작업 규칙**이다.
설계 전체는 @docs/ARCHITECTURE.md — 코드를 만지기 전에 관련 절을 읽는다. 두 문서가 어긋나면 이 파일이 이긴다.

## 0. 이 프로젝트는

- 공연 좌석 선착순 예매 시스템. 대기열 → 좌석 홀드 → 결제 → 확정. 웹(모바일 웹) + 앱.
- 목적은 서비스 출시가 아니라 **기술 습득 + 면접 포트폴리오**. "왜 이 기술인가"를 사람이 설명할 수 있는 선택만 한다.
- 핵심 가치: 수천 명이 같은 좌석을 동시에 잡아도 **이중 예매 0건**. 화면은 이걸 보여주는 껍데기.

**설계 핵심 5줄** (자세한 건 ARCHITECTURE.md 4절)

1. 헥사고날: `adapter → application → domain` 한 방향. 도메인은 스프링·JPA를 모른다.
2. JPA 엔티티 ≠ 도메인 객체. 어댑터에서 변환한다.
3. Redis는 날아가도 되는 것만(대기열, 홀드, 입장 결과, 멱등 키), 키마다 TTL 필수. DB가 확정된 사실.
4. **Redis가 죽어도 같은 좌석이 두 번 확정되면 안 된다** → `confirmed_seat UNIQUE`가 최종 방어.
5. 이벤트는 DB에 먼저 적고(Outbox) 나중에 보낸다. 컨슈머는 멱등.

## 1. 현재 단계

**STAGE = 3**  (사람이 올린다. 에이전트는 바꾸지 않는다. — 2026-09-10 사람 지시로 3으로 올림, v2-web 태그 완료)

| 단계 | 만드는 것 | 태그 |
|---|---|---|
| **1** | 백엔드 뼈대. 한 앱 안에 `catalog` + `queue` + `reservation`. 결제는 mock 어댑터 **동기 호출** | `v1-monolith` |
| 2 | 웹 프론트 (모바일 웹). 좌석맵 Canvas, SSE | `v2-web` |
| 3 | 서비스 분리 + Kafka. `payment` 분리, Outbox, 멱등, 되돌리기, `notification` | `v3-msa` |
| 4 | 수치 + 가상 경쟁자 데모 모드 | `v4-bench` |
| 5 | 모바일 앱 (Expo) | `v5-app` |

- 현재 단계 밖의 것은 **만들지도, 라이브러리를 추가하지도 않는다.** 필요해 보이면 `docs/backlog.md`에 한 줄.
- 5단계는 4단계와 시간상 병행 가능하나 STAGE는 하나. 병행은 사람이 프롬프트로 명시.

### STAGE 1 체크리스트 (사람이 체크한다. 위에서부터 순서대로)

- [x] Gradle(Kotlin DSL) + Spring Boot + Modulith 프로젝트. Compose `infra` 프로필(MySQL, Redis). `./gradlew test` 빈 통과
- [x] Flyway `V1__init.sql`(catalog 4 테이블, reservation, confirmed_seat, outbox) + `V2__seed.sql`(공연 1, 회차 1, 4구역 × 500석)
- [x] `catalog` 모듈: 공연 목록/상세, 좌석 배치, 좌석 상태 비트맵 API (읽기 전용)
- [x] `reservation` 도메인: 상태 전이, `confirm()`의 만료 검사 — 스프링 없이 단위 테스트
- [x] `HoldSeatUseCase` + `SeatHoldStore`(Redis `SET NX EX`) + `ReservationRepository`(JPA, 엔티티↔도메인 매핑) + Outbox 행 기록
- [x] `ConfirmReservationUseCase` + `PaymentGateway`(Mock, 지연·실패율 설정) + `confirmed_seat` UNIQUE 처리
- [x] `ExpireHoldUseCase` + 만료 스케줄러(10초, DB 기준) / `CancelReservationUseCase`
- [x] REST 어댑터 + `Idempotency-Key` + Problem Details 에러 + Testcontainers 통합 테스트
- [x] 동시성 테스트: 1석 100요청 → 성공 1. 확정 UNIQUE 충돌 테스트
- [x] `queue` 모듈: ZSET 진입/순번, 스케줄러 입장(N명), JWT 발급. `reservation`은 JWT 서명만 검증
- [x] ArchUnit + `ApplicationModules.verify()` 통과, GitHub Actions에서 `gradlew test`
- [x] README 로드맵·수치 자리 정리 후 `git tag v1-monolith`

### STAGE 2 체크리스트 (사람이 체크한다. 위에서부터 순서대로)

- [x] 백엔드에 springdoc-openapi 추가, `/v3/api-docs`에서 openapi.json 확인
- [x] pnpm 모노레포: `apps/web`(Next.js App Router + TS + TanStack Query + Tailwind) + `packages/api-client`(openapi 타입 생성, `pnpm gen:api`) + `packages/seat-map-core` 골격
- [x] 공연 목록·상세 화면 — 서버 렌더링(SSR), GATE 디자인 토큰 적용
- [x] 대기열 화면 + 백엔드 `GET /schedules/{id}/queue/stream`(SSE) — 폴링 폴백 포함
- [x] `seat-map-core`: 비트맵 디코딩·히트 테스트 (렌더러 무관 순수 TS, 단위 테스트)
- [x] 좌석맵 화면 — Canvas 렌더링 + `GET /schedules/{id}/seats/stream`(SSE, 변경분만)
- [x] 홀드 → 결제 → 완료 화면 — 멱등 키 재시도, 낙관적 UI, 홀드 카운트다운
- [x] Playwright E2E(대기열→좌석→결제, 모바일 에뮬레이션) + CI에 웹 테스트 추가, README GIF 후 `git tag v2-web`

### STAGE 3 체크리스트 (사람이 체크한다. 위에서부터 순서대로)

- [ ] Compose `stage3` 프로필(Kafka KRaft, Prometheus, Grafana, Tempo) 기동 — 토픽 생성·메시지 왕복 확인
- [ ] `V4__stage3.sql`(`payment`, `processed_event` — PK `(consumer, event_id)`) + shared에 이벤트 봉투(`{eventId, eventType, version, occurredAt, aggregateId, payload}`)와 토픽 규약(`reservation.events`·`payment.events`·`queue.events`, 파티션 키 = scheduleId) 정의
- [ ] Outbox 릴레이 — 직접 폴링(1초, 100건, `published_at` 갱신) vs `spring-modulith-events-kafka` 외부화 비교 ADR 후 구현. Kafka Testcontainers로 발행 검증
- [ ] `payment` 모듈 신설 — 결제 트리거 ADR(확정 요청이 발행하는 `PaymentRequested`를 컨슘하는 안을 기본으로, `ReservationHeld` 직결(홀드 즉시 자동 결제) 안과 비교) 후 구현. mock 승인(지연·실패율) → 거절은 `PaymentDeclined`(HELD 유지, 재시도 가능), 타임아웃·시스템 오류는 `PaymentFailed` 발행. payment 테이블 기록
- [ ] `reservation` 멱등 컨슈머 — `processed_event` insert를 같은 트랜잭션에, `PaymentApproved` → 확정 / `PaymentFailed` → 홀드 해제 / `PaymentDeclined` → HELD 유지. 같은 이벤트 2번 → 1번 처리 테스트
- [ ] 확정 API 전환 — `POST /reservations/{id}/confirm`을 202 + 결과는 조회/SSE로. 웹 결제 화면이 결과를 기다리는 방식 대응
- [ ] 되돌리기 — 결제 타임아웃(응답 없음) → `ExpireHoldUseCase` 경유 홀드 해제 + `EXPIRED`. Resilience4j로 PG 호출 보호(타임아웃·재시도·서킷)
- [ ] 컨슈머 재시도(backoff 3회) → DLT, 실패 메시지 재처리 절차 문서화
- [ ] `queue`: `QueueAdmitted`를 Outbox 경유 `queue.events`로 발행 — notification의 입력
- [ ] `notification` 모듈 골격 — `QueueAdmitted` 컨슘, 푸시 대신 로그(실 푸시는 5단계)
- [ ] 서비스 분리 — Gradle 서브프로젝트로 `payment`를 별도 실행 단위로(선택: `queue`). 분리 범위·방식은 ADR, ArchUnit·Modulith 검증 유지
- [ ] 트레이싱 — OpenTelemetry → Tempo, Grafana에서 홀드→확정 한 흐름을 traceId 하나로 확인. README·ARCHITECTURE 갱신 후 `git tag v3-msa`

## 2. 작업 규칙

- 시작 전: STAGE 확인 → 체크리스트에서 다음 항목 확인 → `docs/adr/` 읽기 → **계획 3~5줄 제시** → 승인 후 코드.
- 커밋은 작게 (`feat(reservation): …`). 도메인 → 유스케이스 → 어댑터 → 테스트 순으로 나눠 커밋. 각 커밋에서 테스트 통과.
- 테스트 없는 코드는 없다. 도메인 = 스프링 없이 단위 테스트. 어댑터 = Testcontainers. 동시성 = 1석 100요청 → 성공 1.
- 목킹은 외부 시스템(PG, Expo Push)만. DB·Redis·Kafka는 실물(Testcontainers).
- 라이브러리 최신 API가 불확실하면 추측하지 말고 **context7 MCP**로 공식 문서를 확인한다. (Spring Boot·Modulith·Next.js·Expo는 버전 변화가 빠름)
- (2단계부터) 화면 동작·렌더링 확인은 **playwright MCP**로 직접 열어 본다. MCP 설정은 `.mcp.json`, 권한은 `.claude/settings.json`.
- 선택지가 둘 이상이었으면 `docs/adr/NNNN-제목.md` (문맥 / 선택지 / 결정 / 결과, 10줄).
- 작업 끝에 **면접 설명용 요약 3줄** (무엇을, 왜, 트레이드오프). 사람이 설명 못 하는 코드는 머지하지 않는다.
- 코드·식별자 영어, 주석·문서 한국어. 에러 코드는 `SEAT_ALREADY_HELD` 형식.
- **금지**: 단계 밖 기능, 목록 밖 라이브러리(ARCHITECTURE.md 4-1), 도메인에 프레임워크 import, JPA 엔티티를 도메인으로 겸용, TTL 없는 Redis 키, `@Transactional` 없는 상태 변경, `ddl-auto: update`, 손으로 쓴 API 타입, react-native-web으로 UI 공유, 모듈 간 `domain`/`adapter` 직접 참조.

## 3. 실행

```bash
docker compose --profile infra up -d      # MySQL, Redis
docker compose --profile stage3 up -d     # + Kafka, Prometheus, Grafana, Tempo (3단계)
cd backend && ./gradlew test && ./gradlew bootRun
pnpm -F web dev                           # 2단계
pnpm gen:api                              # openapi.json → packages/api-client
pnpm -F mobile start                      # 5단계 (EAS 개발 빌드 설치 후)
k6 run load/hold-seat.js                  # 4단계
```

## 4. 문서

- `docs/ARCHITECTURE.md` — 설계 전체: 구성도, 스택, 코드 구조, 도메인·테이블, 이벤트, API 초안, 웹, 앱, 계약, 인프라, 테스트·측정, 면접 포인트, 용어집
- `docs/adr/` — 결정 기록 (`0001`부터. 형식: 문맥 / 선택지 / 결정 / 결과, 10줄) · `docs/backlog.md` — 단계 밖 아이디어
- `README.md` — 소개, 구성도, 로드맵, 수치
