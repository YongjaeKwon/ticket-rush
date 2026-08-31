// 좌석 배치 파싱, 상태 비트맵 디코딩, 히트 테스트 — 렌더러(Canvas/Skia)를 모르는 순수 TS.
// 구현은 STAGE 2 체크리스트 5번(좌석맵)에서 채운다.
export const SEAT_STATUS = {
  FREE: 0b00,
  HELD: 0b01,
  CONFIRMED: 0b10,
  BLOCKED: 0b11,
} as const;
