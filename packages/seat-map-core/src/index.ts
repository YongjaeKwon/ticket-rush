// 좌석 배치 파싱, 상태 비트맵 디코딩, 히트 테스트 — 렌더러(Canvas/Skia)를 모르는 순수 TS.
// 웹(2단계)과 앱(5단계)이 이 패키지를 그대로 공유한다.
export { SEAT_STATUS, statusAt, decodeSectionBitmap, decodeBase64 } from "./status";
export type { SeatStatus } from "./status";
export { layoutGeometry, hitTest } from "./geometry";
export type {
  LayoutLike,
  MapGeometry,
  SeatGeometry,
  SectionGeometry,
  GeometryOptions,
} from "./geometry";
