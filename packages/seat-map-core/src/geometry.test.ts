import { describe, expect, it } from "vitest";
import { hitTest, layoutGeometry, type LayoutLike } from "./geometry";

// 2행 × 3열짜리 작은 구역 — 손으로 좌표를 검산할 수 있는 크기
function smallLayout(): LayoutLike {
  const seats = [];
  let id = 1;
  for (let row = 1; row <= 2; row++) {
    for (let col = 1; col <= 3; col++) {
      seats.push({ id: id++, rowNo: row, colNo: col });
    }
  }
  return { sections: [{ id: 10, name: "A", seats }] };
}

describe("배치 좌표 계산", () => {
  it("좌석마다 (행, 열)에서 픽셀 좌표가 나온다", () => {
    // seatSize 26 + gap 6 = 셀 32, 헤더 24
    const geometry = layoutGeometry(smallLayout());

    const first = geometry.seats[0]; // 1행 1열
    expect([first.x, first.y]).toEqual([0, 24]);
    const last = geometry.seats[5]; // 2행 3열
    expect([last.x, last.y]).toEqual([64, 56]);
    // 폭 = 3열 × 32 - 6 = 90
    expect(geometry.width).toBe(90);
  });

  it("비트맵 인덱스는 구역 내 배치 순서(행→열)와 같다", () => {
    const geometry = layoutGeometry(smallLayout());

    expect(geometry.seats.map((s) => s.index)).toEqual([0, 1, 2, 3, 4, 5]);
  });

  it("구역 두 개는 세로로 쌓인다", () => {
    const layout: LayoutLike = {
      sections: [
        { id: 1, name: "A", seats: [{ id: 1, rowNo: 1, colNo: 1 }] },
        { id: 2, name: "B", seats: [{ id: 2, rowNo: 1, colNo: 1 }] },
      ],
    };

    const geometry = layoutGeometry(layout);

    const [a, b] = geometry.sections;
    expect(b.y).toBeGreaterThan(a.y + a.height); // 구역 간격만큼 아래에
    expect(geometry.seats[1].y).toBeGreaterThan(geometry.seats[0].y);
  });

  it("실제 규모(4구역 x 500석 = 2,000석)도 좌표가 전부 나온다", () => {
    const sections = Array.from({ length: 4 }, (_, s) => ({
      id: s + 1,
      name: "ABCD"[s],
      seats: Array.from({ length: 500 }, (_, i) => ({
        id: s * 500 + i + 1,
        rowNo: Math.floor(i / 25) + 1,
        colNo: (i % 25) + 1,
      })),
    }));

    const geometry = layoutGeometry({ sections });

    expect(geometry.seats).toHaveLength(2000);
    expect(new Set(geometry.seats.map((s) => `${s.x},${s.y}`)).size).toBe(2000); // 겹침 없음
  });
});

describe("히트 테스트", () => {
  const geometry = layoutGeometry(smallLayout());

  it("좌석 위를 누르면 그 좌석이 잡힌다", () => {
    // 2행 3열(id 6)의 중앙 근처: x=64+13, y=56+13
    expect(hitTest(geometry, 77, 69)?.seatId).toBe(6);
  });

  it("좌석 사이 간격을 누르면 null", () => {
    // 1열과 2열 사이(x=26~31)는 빈 공간
    expect(hitTest(geometry, 28, 30)).toBeNull();
  });

  it("맵 밖을 누르면 null", () => {
    expect(hitTest(geometry, -5, 10)).toBeNull();
    expect(hitTest(geometry, 500, 500)).toBeNull();
  });
});
