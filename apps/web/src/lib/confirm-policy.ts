// 확정(결제) 실패를 "다음에 무엇을 하나"로 분류하는 순수 함수 — 화면은 이 결정을 그리기만 한다.
// 근거는 서버 IdempotencyFilter의 규칙 두 줄:
//   · 2xx·4xx 응답은 24시간 저장해 같은 접수번호(Idempotency-Key)면 그대로 재생한다
//   · 5xx는 저장하지 않는다
// 그래서 "결과를 모르면 같은 키(재생이 이중 결제를 막는다), 서버가 답을 줬으면 새 키(같은 키는 그 답만 반복)".
import { ApiError } from "@ticket-rush/api-client";

export type ConfirmFailure =
  /** 402 PAYMENT_DECLINED — 홀드는 살아 있다. 사용자가 새 접수번호로 다시 시도할 수 있다 */
  | { kind: "declined" }
  /** 만료·상태 충돌 — "잃어버린 성공"일 수 있으니 서버 상태를 다시 읽고 결정한다 */
  | { kind: "verify"; code: string }
  /** 403·404 — 이 브라우저의 예매가 아니거나 없다. 더 진행할 수 없다 */
  | { kind: "gone"; code: string; message: string }
  /** 5xx — 서버가 저장하지 않았다. 같은 키를 유지하되, PG 승인 뒤에 넘어졌을 수 있으니 바로 재전송하지 않고 조회로 먼저 확인한다 */
  | { kind: "server"; status: number }
  /** 네트워크 오류·타임아웃 — 서버가 처리했을 수도 있다. 같은 키만 허용 */
  | { kind: "unknown" }
  /** 그 밖의 4xx — 서버가 판정을 끝냈다. 키를 버리고 새 시도 */
  | { kind: "rejected"; code: string; message: string };

const VERIFY_CODES = new Set(["HOLD_EXPIRED", "INVALID_RESERVATION_STATE", "SEAT_ALREADY_CONFIRMED"]);

export function classifyConfirmFailure(error: unknown): ConfirmFailure {
  if (!(error instanceof ApiError)) return { kind: "unknown" };
  if (error.status >= 500) return { kind: "server", status: error.status };
  if (error.code === "PAYMENT_DECLINED") return { kind: "declined" };
  if (VERIFY_CODES.has(error.code)) return { kind: "verify", code: error.code };
  if (error.status === 403 || error.status === 404) {
    return { kind: "gone", code: error.code, message: error.message };
  }
  return { kind: "rejected", code: error.code, message: error.message };
}

/** 같은 접수번호를 계속 써야 하는가 — 결과를 모르거나(unknown) 서버가 저장하지 않은(5xx) 경우만. */
export function keepsIdempotencyKey(failure: ConfirmFailure): boolean {
  return failure.kind === "unknown" || failure.kind === "server";
}
