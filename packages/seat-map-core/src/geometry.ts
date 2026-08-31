// 좌석 배치 → 픽셀 좌표, 그리고 탭 좌표 → 좌석 판정(히트 테스트).
// 렌더러(Canvas/Skia)를 모른다 — 숫자만 계산하고 그리는 건 바깥의 일이다.

/** API 배치 응답과 구조적으로 호환되는 최소 형태 */
export interface LayoutLike {
  sections: {
    id: number;
    name: string;
    seats: { id: number; rowNo: number; colNo: number }[];
  }[];
}

export interface SeatGeometry {
  seatId: number;
  sectionId: number;
  /** 비트맵 인덱스 — 구역 내 배치 순서(행→열). 상태 조회의 열쇠다 */
  index: number;
  x: number;
  y: number;
}

export interface SectionGeometry {
  sectionId: number;
  name: string;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface MapGeometry {
  seats: SeatGeometry[];
  sections: SectionGeometry[];
  width: number;
  height: number;
  seatSize: number;
}

export interface GeometryOptions {
  seatSize?: number;      // 좌석 한 변 (디자인 규격: 정사각)
  gap?: number;           // 좌석 사이 간격
  sectionGap?: number;    // 구역 사이 세로 간격
  sectionHeader?: number; // 구역 라벨 높이
}

const DEFAULTS: Required<GeometryOptions> = {
  seatSize: 26,
  gap: 6,
  sectionGap: 28,
  sectionHeader: 24,
};

/** 구역을 세로로 쌓으며 좌석마다 (x, y)를 계산한다. */
export function layoutGeometry(layout: LayoutLike, options: GeometryOptions = {}): MapGeometry {
  const { seatSize, gap, sectionGap, sectionHeader } = { ...DEFAULTS, ...options };
  const cell = seatSize + gap;

  const seats: SeatGeometry[] = [];
  const sections: SectionGeometry[] = [];
  let offsetY = 0;
  let mapWidth = 0;

  for (const section of layout.sections) {
    const rows = Math.max(...section.seats.map((s) => s.rowNo), 0);
    const cols = Math.max(...section.seats.map((s) => s.colNo), 0);
    const width = cols * cell - gap;
    const height = rows * cell - gap;

    const bodyY = offsetY + sectionHeader;
    section.seats.forEach((seat, index) => {
      seats.push({
        seatId: seat.id,
        sectionId: section.id,
        index,
        x: (seat.colNo - 1) * cell,
        y: bodyY + (seat.rowNo - 1) * cell,
      });
    });

    sections.push({
      sectionId: section.id,
      name: section.name,
      x: 0,
      y: offsetY,
      width,
      height: sectionHeader + height,
    });

    mapWidth = Math.max(mapWidth, width);
    offsetY = bodyY + height + sectionGap;
  }

  return {
    seats,
    sections,
    width: mapWidth,
    height: Math.max(0, offsetY - sectionGap),
    seatSize,
  };
}

/** 탭/클릭 좌표가 어느 좌석 위인지. 간격 위를 누르면 null. */
export function hitTest(geometry: MapGeometry, x: number, y: number): SeatGeometry | null {
  const size = geometry.seatSize;
  for (const seat of geometry.seats) {
    if (x >= seat.x && x < seat.x + size && y >= seat.y && y < seat.y + size) {
      return seat;
    }
  }
  return null;
}
