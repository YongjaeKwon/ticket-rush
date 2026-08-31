import { describe, expect, it } from "vitest";
import { SEAT_STATUS, decodeBase64, decodeSectionBitmap, statusAt } from "./status";

// 백엔드 SeatStatusBitmapTest와 같은 케이스로 인코더↔디코더 계약을 맞춘다.
describe("비트맵 디코딩", () => {
  it("백엔드가 인코딩한 확정 위치를 정확히 읽는다", () => {
    // 좌석 8개, 3번째(index 2)와 8번째(index 7)가 확정 — 백엔드 테스트와 동일 값
    const bytes = new Uint8Array([0b00001000, 0b00000010]);

    const statuses = decodeSectionBitmap(bytes, 8);

    expect(Array.from(statuses)).toEqual([0, 0, 2, 0, 0, 0, 0, 2]);
    expect(statusAt(bytes, 2)).toBe(SEAT_STATUS.CONFIRMED);
    expect(statusAt(bytes, 0)).toBe(SEAT_STATUS.FREE);
  });

  it("한 바이트에 4좌석 — 네 가지 상태를 모두 구분한다", () => {
    // 00 01 10 11 → FREE, HELD, CONFIRMED, BLOCKED
    const bytes = new Uint8Array([0b00011011]);

    expect(Array.from(decodeSectionBitmap(bytes, 4))).toEqual([
      SEAT_STATUS.FREE,
      SEAT_STATUS.HELD,
      SEAT_STATUS.CONFIRMED,
      SEAT_STATUS.BLOCKED,
    ]);
  });

  it("500석은 125바이트에서 풀린다", () => {
    const statuses = decodeSectionBitmap(new Uint8Array(125), 500);

    expect(statuses).toHaveLength(500);
    expect(statuses.every((s) => s === SEAT_STATUS.FREE)).toBe(true);
  });

  it("바이트가_모자라면_명확한 에러를 던진다", () => {
    expect(() => decodeSectionBitmap(new Uint8Array(1), 5)).toThrow("비트맵이 짧습니다");
  });

  it("API의 base64 문자열을 바이트로 되돌린다", () => {
    // [0b00001000, 0b00000010] = "CAI="
    const bytes = decodeBase64("CAI=");

    expect(Array.from(bytes)).toEqual([0b00001000, 0b00000010]);
  });
});
