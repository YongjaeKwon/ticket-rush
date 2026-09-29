# 0008. 결제 트리거 — 확정 요청이 발행하는 PaymentRequested (3단계)
날짜: 2026-09-11 · 단계: 3 · 상태: 결정 · [English](0008-payment-trigger-payment-requested.en.md)
## 문맥
payment 모듈은 쪽지(이벤트)를 받아 결제를 시작한다. 무엇을 받을지가 문제다. 설계 문서 초안(ARCHITECTURE 2-1)은 `ReservationHeld` 직결을 그렸지만, 2단계에서 만든 결제 화면은 사용자가 결제수단을 고르고 버튼을 눌러야 결제가 시작되는 흐름이고, 거절 후 재시도(홀드 유지, ADR 0006)도 사용자 트리거를 전제한다.
## 선택지
- **확정 요청이 `PaymentRequested`를 발행** (채택): 사용자가 결제 버튼을 누르면 확정 API가 Outbox에 `PaymentRequested`를 적고 202로 응답한다(전환은 다음 단위). payment는 이 쪽지만 컨슘한다.
- `ReservationHeld` 직결: 홀드 즉시 자동 결제. 흐름은 짧지만 사용자가 카드 폼을 제출하기 전에 결제가 끝나 버려 2단계 화면·재시도 규칙과 양립하지 않고, 확정 API가 할 일이 없어진다.
## 결정
`PaymentRequested`. 사용자의 "결제하기"가 진실의 시작점이라는 2단계의 결정을 3단계가 그대로 잇는다. 이벤트에는 결제에 필요한 것만 담는다 — reservationId, scheduleId(파티션 키), userId, amount. 결과는 `PaymentApproved` / `PaymentDeclined`(카드 거절 — 홀드 유지, 재시도 가능) / `PaymentFailed`(타임아웃·시스템 오류 — 되돌리기 대상)로 나눠 발행한다. 거절과 실패를 나누는 이유는 ARCHITECTURE 2-2 그대로 — 거절은 상태 전이가 아니고, 실패만 홀드 해제로 이어진다.
## 결과
얻는 것: 화면·재시도 규칙과 모순 없는 이벤트 흐름, ARCHITECTURE 2-1 그림은 이 결정에 맞춰 수정한다. 잃는 것: 홀드 → 결제 사이에 이벤트 한 장이 늘어 왕복이 하나 추가된다(홀드 직결이었다면 없었을 지연). 확정 API의 202 전환 전까지는 payment 모듈이 프로덕션 트래픽을 받지 않는다 — 이 단위에서는 테스트가 쪽지를 직접 넣어 검증한다.
