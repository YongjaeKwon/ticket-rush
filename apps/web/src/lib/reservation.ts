// 예매 API 호출 모음 — 경로·타입은 생성 스키마에 1:1로 묶는다 (손으로 타입을 적지 않는다).
import { api, type components } from "@ticket-rush/api-client";
import { getUserId } from "./user";

export type Reservation = components["schemas"]["ReservationResponse"];
export type HoldResult = components["schemas"]["HoldResponse"];
export type ConfirmResult = components["schemas"]["ConfirmResponse"];
export type ScheduleDetail = components["schemas"]["ScheduleDetailResponse"];
export type SeatLayout = components["schemas"]["SeatLayoutResponse"];

/**
 * 홀드·결제 응답을 기다려 줄 시간. 넘기면 "결과를 모르는" 상태로 본다 —
 * 먼저 조회(GET)로 확인하고, 그래도 모르면 같은 접수번호로 다시 보낸다.
 */
export const HOLD_TIMEOUT_MS = 10_000;
export const CONFIRM_TIMEOUT_MS = 10_000;

/** 생성 타입은 필드를 전부 선택으로 잡는다 — 서버 계약상 항상 오는 값은 여기서 확정한다. */
function required<T>(value: T | undefined, field: string): T {
  if (value === undefined || value === null) {
    throw new Error(`응답에 ${field}가 없습니다`);
  }
  return value;
}

export function getReservation(reservationId: number): Promise<Reservation> {
  return api<Reservation>(`/api/reservations/${reservationId}`, {
    headers: { "X-User-Id": getUserId() },
    cache: "no-store",
  });
}

/** 좌석 홀드. 접수번호는 호출자가 관리한다 — 연결이 끊겨 결과를 모르면 같은 키로 다시 보낸다. */
export async function holdSeat(
  scheduleId: number,
  seatId: number,
  admissionToken: string,
  idempotencyKey: string,
): Promise<{ reservationId: number; expiresAt: string }> {
  const result = await api<HoldResult>("/api/reservations", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-User-Id": getUserId(),
      Authorization: `Bearer ${admissionToken}`,
      "Idempotency-Key": idempotencyKey,
    },
    body: JSON.stringify({ scheduleId, seatId }),
    signal: AbortSignal.timeout(HOLD_TIMEOUT_MS),
  });
  return {
    reservationId: required(result.reservationId, "reservationId"),
    expiresAt: required(result.expiresAt, "expiresAt"),
  };
}

/**
 * 결제 승인 → 확정. 접수번호(Idempotency-Key)는 호출자가 관리한다 —
 * 같은 시도의 재전송이면 같은 키, 사용자가 새로 시도하는 것이면 새 키.
 */
export function confirmReservation(
  reservationId: number,
  idempotencyKey: string,
): Promise<ConfirmResult> {
  return api<ConfirmResult>(`/api/reservations/${reservationId}/confirm`, {
    method: "POST",
    headers: { "X-User-Id": getUserId(), "Idempotency-Key": idempotencyKey },
    signal: AbortSignal.timeout(CONFIRM_TIMEOUT_MS),
  });
}

export function getSchedule(scheduleId: number): Promise<ScheduleDetail> {
  return api<ScheduleDetail>(`/api/schedules/${scheduleId}`);
}

/** 배치는 불변 캐시 — 좌석맵에서 이미 받았으면 브라우저 캐시에서 온다. */
export function getSeatLayout(scheduleId: number): Promise<SeatLayout> {
  return api<SeatLayout>(`/api/schedules/${scheduleId}/seats/layout`);
}

/** seatId → "A구역 1열 1번" */
export function describeSeat(layout: SeatLayout, seatId: number): string | null {
  for (const section of layout.sections ?? []) {
    const seat = section.seats?.find((s) => s.id === seatId);
    if (seat) return `${section.name}구역 ${seat.rowNo}열 ${seat.colNo}번`;
  }
  return null;
}

/* ── 확정 접수번호(Idempotency-Key) 보관 ───────────────────────────
   서버는 같은 키의 응답을 24시간 저장해 두고 그대로 재생한다(4xx 포함, 5xx 제외).
   그래서 키의 수명은 "한 번의 결제 시도"와 같다:
   - 네트워크 오류·타임아웃·5xx로 결과를 모를 때 → 먼저 조회로 확인하고, 그래도 모르면 같은 키로 다시 보낸다 (두 번 결제되지 않는다)
   - PG가 거절해서 사용자가 다시 시도할 때 → 키를 버리고 새로 만든다 (같은 키면 거절 응답이 재생된다)
   새로고침에도 살아야 하므로 sessionStorage에 둔다. */
const confirmKeyName = (reservationId: number) => `tr-confirm-key-${reservationId}`;

export function currentConfirmKey(reservationId: number): string {
  let key = sessionStorage.getItem(confirmKeyName(reservationId));
  if (!key) {
    key = crypto.randomUUID();
    sessionStorage.setItem(confirmKeyName(reservationId), key);
  }
  return key;
}

export function discardConfirmKey(reservationId: number) {
  sessionStorage.removeItem(confirmKeyName(reservationId));
}

/** 키가 남아 있다 = 결과를 못 받은 시도가 있다 (성공·거절이면 그 자리에서 버리므로). */
export function hasConfirmKey(reservationId: number): boolean {
  return sessionStorage.getItem(confirmKeyName(reservationId)) !== null;
}

/* ── 내 활성 홀드 포인터 ──────────────────────────────────────
   좌석맵으로 돌아왔을 때(뒤로가기·새로고침) 내 홀드를 남의 홀드(회색)로 오인하지 않도록
   회차별로 하나만 기억한다. 진실은 서버 — 좌석맵이 마운트 때 GET으로 확인하고 끝난 홀드면 지운다. */
export type ActiveHold = { reservationId: number; seatId: number; expiresAt: string };
const activeHoldName = (scheduleId: number) => `tr-hold-${scheduleId}`;

export function saveActiveHold(scheduleId: number, hold: ActiveHold) {
  sessionStorage.setItem(activeHoldName(scheduleId), JSON.stringify(hold));
}

export function loadActiveHold(scheduleId: number): ActiveHold | null {
  const raw = sessionStorage.getItem(activeHoldName(scheduleId));
  return raw ? (JSON.parse(raw) as ActiveHold) : null;
}

export function clearActiveHold(scheduleId: number) {
  sessionStorage.removeItem(activeHoldName(scheduleId));
}

/* 확정 응답(PG 승인번호)은 조회 API에 없다 — 완료 화면에 보여주려고 잠시 들고 간다 */
const confirmResultName = (reservationId: number) => `tr-confirm-result-${reservationId}`;

export function saveConfirmResult(reservationId: number, result: ConfirmResult) {
  sessionStorage.setItem(confirmResultName(reservationId), JSON.stringify(result));
}

export function readConfirmResult(reservationId: number): ConfirmResult | null {
  const raw = sessionStorage.getItem(confirmResultName(reservationId));
  return raw ? (JSON.parse(raw) as ConfirmResult) : null;
}
