// 좌석 상태 비트맵 디코더 — 백엔드 SeatStatusBitmap(인코더)의 거울이다.
// 계약(ARCHITECTURE 8-3): 좌석당 2비트, 한 바이트에 4좌석, 상위 비트부터.
// i번째 좌석 = bytes[i/4]의 (6 - (i%4)*2) 시프트 위치.

export const SEAT_STATUS = {
  FREE: 0b00,
  HELD: 0b01,
  CONFIRMED: 0b10,
  BLOCKED: 0b11,
} as const;

export type SeatStatus = (typeof SEAT_STATUS)[keyof typeof SEAT_STATUS];

/** i번째 좌석의 상태를 읽는다. */
export function statusAt(bytes: Uint8Array, index: number): SeatStatus {
  return ((bytes[index >> 2] >> (6 - (index % 4) * 2)) & 0b11) as SeatStatus;
}

/** 구역 비트맵 전체를 상태 배열로 푼다. 2,000석이어도 0.1ms 수준이다. */
export function decodeSectionBitmap(bytes: Uint8Array, seatCount: number): Uint8Array {
  if (bytes.length < Math.ceil(seatCount / 4)) {
    throw new Error(`비트맵이 짧습니다: ${bytes.length}바이트로 ${seatCount}석을 담을 수 없음`);
  }
  const statuses = new Uint8Array(seatCount);
  for (let i = 0; i < seatCount; i++) {
    statuses[i] = statusAt(bytes, i);
  }
  return statuses;
}

/** API의 base64 문자열 → 바이트. 브라우저(atob)와 Node(Buffer) 양쪽에서 돈다. */
export function decodeBase64(base64: string): Uint8Array {
  if (typeof atob === "function") {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }
  return new Uint8Array(Buffer.from(base64, "base64"));
}
