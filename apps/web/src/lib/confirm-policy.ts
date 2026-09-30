// 확정(결제)을 "다음에 무엇을 하나"로 분류하는 순수 함수들 — 화면은 이 결정을 그리기만 한다.
// 3단계부터 확정은 비동기다(ADR 0009): POST는 202로 "접수"만 하고, 판정은 조회(GET)로 받는다.
//
// 접수번호(Idempotency-Key) 규칙의 근거는 서버 IdempotencyFilter 두 줄:
//   · 2xx·4xx 응답은 24시간 저장해 같은 키면 그대로 재생한다 (202도 저장된다)
//   · 5xx는 저장하지 않는다
// 그래서 키의 수명 = 결제 시도 하나 = 결제 요청 쪽지 한 장.
//   · 접수(202)됐으면 판정이 날 때까지 같은 키 — 같은 키로 다시 보내면 202가 재생될 뿐 쪽지가 또 나가지 않는다
//   · 결과를 모르면 같은 키 — 서버에 닿았으면 재생, 안 닿았으면 그때 처음 처리된다(어느 쪽이든 한 장)
//   · 판정(승인·거절·실패)이 나면 키를 버린다 — 거절 뒤 재시도는 새 키여야 쪽지가 새로 나간다
import { ApiError } from "@ticket-rush/api-client";

export type ConfirmFailure =
  /** 409 PAYMENT_IN_PROGRESS — 다른 시도(다른 탭·잃어버린 응답)가 결제 중이다. 이 시도는 거부됐으니 키를 버리고 그 결과를 기다린다 */
  | { kind: "inProgress" }
  /** 만료·상태 충돌 — "잃어버린 성공"일 수 있으니 서버 상태를 다시 읽고 결정한다 */
  | { kind: "verify"; code: string }
  /** 403·404 — 이 브라우저의 예매가 아니거나 없다. 더 진행할 수 없다 */
  | { kind: "gone"; code: string; message: string }
  /** 5xx — 서버가 저장하지 않았다. 같은 키를 유지하되, 처리됐을 수도 있으니 바로 재전송하지 않고 조회로 먼저 확인한다 */
  | { kind: "server"; status: number }
  /** 네트워크 오류·타임아웃 — 서버가 처리했을 수도 있다. 같은 키만 허용 */
  | { kind: "unknown" }
  /** 그 밖의 4xx — 서버가 판정을 끝냈다. 키를 버리고 새 시도 */
  | { kind: "rejected"; code: string; message: string };

const VERIFY_CODES = new Set(["HOLD_EXPIRED", "INVALID_RESERVATION_STATE"]);

export function classifyConfirmFailure(error: unknown): ConfirmFailure {
  if (!(error instanceof ApiError)) return { kind: "unknown" };
  if (error.status >= 500) return { kind: "server", status: error.status };
  if (error.code === "PAYMENT_IN_PROGRESS") return { kind: "inProgress" };
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

/** 조회 결과가 결제 시도에 대해 말해 주는 것 */
export type PaymentOutcome =
  /** 확정 — 완료 화면으로 */
  | "confirmed"
  /** 결제 요청 뒤 판정 대기 중 — 계속 기다린다 */
  | "pending"
  /** 카드 거절 — 선점은 살아 있다. 새 키로 다시 시도할 수 있다 */
  | "declined"
  /** 결제 처리 오류로 좌석이 풀렸다(되돌리기) — 좌석을 다시 골라야 한다 */
  | "failed"
  /** 결제는 승인됐지만 좌석을 확정하지 못했다(선점 만료·이미 확정된 좌석) — 좌석을 다시 골라야 한다 */
  | "lost"
  /** 시간 만료·취소 — 좌석을 다시 골라야 한다 */
  | "ended"
  /** 아직 결제를 요청하지 않은 선점 — 요청이 서버에 닿지 않았을 수 있다 */
  | "open";

export function classifyPaymentOutcome(reservation: {
  status?: string;
  paymentStatus?: string | null;
}): PaymentOutcome {
  switch (reservation.status) {
    case "CONFIRMED":
      return "confirmed";
    case "HELD":
      if (reservation.paymentStatus === "REQUESTED") return "pending";
      if (reservation.paymentStatus === "DECLINED") return "declined";
      return "open";
    case "EXPIRED":
      if (reservation.paymentStatus === "FAILED") return "failed";
      if (reservation.paymentStatus === "APPROVED") return "lost";
      return "ended";
    default:
      return "ended";
  }
}

/** 판정이 난 결과 — 화면은 이걸 이동·안내로 바꾼다 */
export type Verdict = Exclude<PaymentOutcome, "pending" | "open">;

export type ResumeAction =
  /** 결제 중 — 판정을 기다린다 */
  | { kind: "wait" }
  /** 같은 접수번호로 다시 보낸다 — 서버에 닿았으면 저장된 202가 재생되고, 안 닿았으면 그때 처음 처리된다 */
  | { kind: "resend" }
  /** 보내지 않고 "결제 결과 확인"을 유지한다 — 이 탭의 시도가 없는데 서버에 묻지 못했다 */
  | { kind: "stay" }
  /** 확인할 시도가 없다 — 평소의 "결제하기"로 돌아간다 */
  | { kind: "reset" }
  /** 판정을 따른다 */
  | { kind: "settle"; verdict: Verdict };

/**
 * "결제 결과 확인"(이어서 확인)을 눌렀을 때 — 보내기 전에 조회한 결과로 무엇을 할지 정한다.
 * @param outcome 조회 판정 (조회 실패면 null)
 * @param hasKey 이 탭에 판정을 못 본 시도(접수번호)가 있는가
 * @param accepted 그 시도가 202로 접수된 것을 이 탭이 보았는가
 */
export function resumeAction(outcome: PaymentOutcome | null, hasKey: boolean, accepted: boolean): ResumeAction {
  if (outcome === "pending") return { kind: "wait" };
  if (outcome === null) return hasKey ? { kind: "resend" } : { kind: "stay" }; // 조회 실패를 새 결제로 바꾸지 않는다
  if (outcome === "open") return hasKey ? { kind: "resend" } : { kind: "reset" };
  // 거절은 이 탭의 시도가 서버에 닿았는지 모를 때만 애매하다 — 같은 키 재전송이 가려 준다
  if (outcome === "declined" && hasKey && !accepted) return { kind: "resend" };
  return { kind: "settle", verdict: outcome };
}
