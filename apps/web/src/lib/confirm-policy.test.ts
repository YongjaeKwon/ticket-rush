import { describe, expect, it } from "vitest";
import { ApiError } from "@ticket-rush/api-client";
import { classifyConfirmFailure, classifyPaymentOutcome, keepsIdempotencyKey, resumeAction } from "./confirm-policy";

describe("classifyConfirmFailure — 확정 요청(POST) 실패를 다음 행동으로 분류한다", () => {
  it("네트워크 오류·타임아웃은 결과를 모르는 것 → 같은 키 유지", () => {
    const failure = classifyConfirmFailure(new TypeError("Failed to fetch"));
    expect(failure).toEqual({ kind: "unknown" });
    expect(keepsIdempotencyKey(failure)).toBe(true);
  });

  it("5xx는 서버가 저장하지 않으므로 같은 키로 다시 보낸다", () => {
    const failure = classifyConfirmFailure(new ApiError(503, "UNKNOWN", "요청 실패: 503"));
    expect(failure).toEqual({ kind: "server", status: 503 });
    expect(keepsIdempotencyKey(failure)).toBe(true);
  });

  it("결제 중(다른 탭의 시도)이면 이 시도는 거부된 것 → 키를 버리고 그 결과를 기다린다", () => {
    const failure = classifyConfirmFailure(
      new ApiError(409, "PAYMENT_IN_PROGRESS", "결제가 진행 중입니다"),
    );
    expect(failure).toEqual({ kind: "inProgress" });
    expect(keepsIdempotencyKey(failure)).toBe(false);
  });

  it.each(["HOLD_EXPIRED", "INVALID_RESERVATION_STATE"])(
    "%s 는 잃어버린 성공일 수 있어 상태를 다시 읽는다",
    (code) => {
      const failure = classifyConfirmFailure(new ApiError(409, code, "..."));
      expect(failure).toEqual({ kind: "verify", code });
      expect(keepsIdempotencyKey(failure)).toBe(false);
    },
  );

  it("403·404는 진행할 수 없는 상태", () => {
    expect(classifyConfirmFailure(new ApiError(403, "RESERVATION_NOT_OWNED", "본인만"))).toEqual({
      kind: "gone",
      code: "RESERVATION_NOT_OWNED",
      message: "본인만",
    });
    expect(classifyConfirmFailure(new ApiError(404, "RESERVATION_NOT_FOUND", "없음")).kind).toBe("gone");
  });

  it("그 밖의 4xx는 서버가 판정을 끝낸 것 → 키를 버리고 새 시도", () => {
    const failure = classifyConfirmFailure(new ApiError(409, "IDEMPOTENCY_CONFLICT", "꼬임"));
    expect(failure).toEqual({ kind: "rejected", code: "IDEMPOTENCY_CONFLICT", message: "꼬임" });
    expect(keepsIdempotencyKey(failure)).toBe(false);
  });
});

describe("classifyPaymentOutcome — 조회 결과를 결제 시도의 판정으로 읽는다", () => {
  it("CONFIRMED는 확정", () => {
    expect(classifyPaymentOutcome({ status: "CONFIRMED", paymentStatus: "APPROVED" })).toBe("confirmed");
  });

  it("HELD + REQUESTED는 판정 대기 — 거절과 구분되는 표지가 있어야 기다릴지 말지 정한다", () => {
    expect(classifyPaymentOutcome({ status: "HELD", paymentStatus: "REQUESTED" })).toBe("pending");
  });

  it("HELD + DECLINED는 거절 — 선점은 살아 있어 다시 시도할 수 있다", () => {
    expect(classifyPaymentOutcome({ status: "HELD", paymentStatus: "DECLINED" })).toBe("declined");
  });

  it("결제 요청 전 HELD는 open — 요청이 서버에 닿지 않았을 수 있다", () => {
    expect(classifyPaymentOutcome({ status: "HELD", paymentStatus: null })).toBe("open");
    expect(classifyPaymentOutcome({ status: "HELD" })).toBe("open");
  });

  it("EXPIRED + FAILED는 결제 오류로 풀린 것 — 시간 만료와 문구가 다르다", () => {
    expect(classifyPaymentOutcome({ status: "EXPIRED", paymentStatus: "FAILED" })).toBe("failed");
    expect(classifyPaymentOutcome({ status: "EXPIRED", paymentStatus: "REQUESTED" })).toBe("ended");
    expect(classifyPaymentOutcome({ status: "EXPIRED", paymentStatus: null })).toBe("ended");
  });

  it("CANCELLED는 끝난 예매", () => {
    expect(classifyPaymentOutcome({ status: "CANCELLED" })).toBe("ended");
  });
});

describe("classifyPaymentOutcome — 승인됐지만 좌석을 놓친 결론", () => {
  it("EXPIRED + APPROVED는 lost — 시간 만료·결제 오류와 다른 안내가 필요하다", () => {
    expect(classifyPaymentOutcome({ status: "EXPIRED", paymentStatus: "APPROVED" })).toBe("lost");
  });
});

describe("resumeAction — '결제 결과 확인'을 누르면 보내기 전 조회로 다음 행동을 정한다", () => {
  it("결제 중이면 기다린다 — 키가 있든 없든", () => {
    expect(resumeAction("pending", true, true)).toEqual({ kind: "wait" });
    expect(resumeAction("pending", false, false)).toEqual({ kind: "wait" });
  });

  it("조회 실패: 이 탭의 시도가 있으면 같은 키로 이어가고, 없으면 새 결제로 바꾸지 않는다", () => {
    expect(resumeAction(null, true, false)).toEqual({ kind: "resend" });
    expect(resumeAction(null, false, false)).toEqual({ kind: "stay" });
  });

  it("요청 전(open): 키가 있으면 요청이 안 닿은 것 → 같은 키로 보내고, 없으면 평소 결제하기로", () => {
    expect(resumeAction("open", true, false)).toEqual({ kind: "resend" });
    expect(resumeAction("open", false, false)).toEqual({ kind: "reset" });
  });

  it("거절: 접수를 못 보았으면 누구의 거절인지 모른다 → 같은 키 재전송이 가려 준다", () => {
    expect(resumeAction("declined", true, false)).toEqual({ kind: "resend" });
  });

  it("거절: 202를 보았거나(이 시도의 거절) 이 탭의 시도가 없으면(남의 거절) 판정을 따른다", () => {
    expect(resumeAction("declined", true, true)).toEqual({ kind: "settle", verdict: "declined" });
    expect(resumeAction("declined", false, false)).toEqual({ kind: "settle", verdict: "declined" });
  });

  it.each(["confirmed", "failed", "ended", "lost"] as const)("%s는 판정 그대로 따른다", (verdict) => {
    expect(resumeAction(verdict, true, false)).toEqual({ kind: "settle", verdict });
    expect(resumeAction(verdict, false, false)).toEqual({ kind: "settle", verdict });
  });
});
