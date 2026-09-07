import { describe, expect, it } from "vitest";
import { ApiError } from "@ticket-rush/api-client";
import { classifyConfirmFailure, keepsIdempotencyKey } from "./confirm-policy";

describe("classifyConfirmFailure — 서버 응답을 다음 행동으로 분류한다", () => {
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

  it("결제 거절은 홀드가 살아 있고, 같은 키면 거절이 재생되므로 키를 버린다", () => {
    const failure = classifyConfirmFailure(
      new ApiError(402, "PAYMENT_DECLINED", "결제가 거절됐습니다. 홀드는 유지 중입니다"),
    );
    expect(failure).toEqual({ kind: "declined" });
    expect(keepsIdempotencyKey(failure)).toBe(false);
  });

  it.each(["HOLD_EXPIRED", "INVALID_RESERVATION_STATE", "SEAT_ALREADY_CONFIRMED"])(
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
