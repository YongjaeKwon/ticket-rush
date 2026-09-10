// E2E 공용 절차 — 화면 동작을 흉내 내지 않고, 화면과 같은 계산 로직(seat-map-core)으로 좌석 좌표를 구한다.
import { expect, type APIRequestContext, type Page } from "@playwright/test";
import {
  SEAT_STATUS,
  decodeBase64,
  decodeSectionBitmap,
  layoutGeometry,
  type LayoutLike,
} from "@ticket-rush/seat-map-core";

export const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

/** 시나리오마다 다른 익명 사용자 — 화면의 getUserId()가 읽는 localStorage 키를 미리 채운다 */
export async function useFreshUser(page: Page): Promise<string> {
  const userId = `e2e-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  await page.addInitScript((id: string) => {
    if (!localStorage.getItem("tr-user-id")) localStorage.setItem("tr-user-id", id);
  }, userId);
  return userId;
}

/** 배치 로드가 끝나 캔버스가 실제 좌석 크기로 그려질 때까지 기다린다 */
export async function waitForSeatCanvas(page: Page) {
  const canvas = page.locator("canvas");
  await expect(canvas).toBeVisible();
  await expect
    .poll(() => canvas.evaluate((el) => (el as HTMLCanvasElement).width))
    .toBeGreaterThan(300);
}

/** 대기열에 들어가 입장될 때까지 기다린 뒤 좌석맵이 그려진 것을 확인한다 */
export async function enterSeatMap(page: Page, scheduleId = 1) {
  await page.goto(`/schedules/${scheduleId}/queue`);
  await page.waitForURL(new RegExp(`/schedules/${scheduleId}/seats`), { timeout: 30_000 });
  await waitForSeatCanvas(page);
}

export type FreeSeat = { seatId: number; label: string; x: number; y: number };

/**
 * 서버의 좌석 상태 비트맵에서 n번째 빈 좌석을 찾아 캔버스 위 클릭 좌표로 바꾼다.
 * 확정된 좌석이 DB에 쌓여도(같은 DB로 여러 번 실행) 항상 빈 좌석을 고른다.
 */
export async function findFreeSeat(
  page: Page,
  request: APIRequestContext,
  { scheduleId = 1, skip = 0 } = {},
): Promise<FreeSeat> {
  const layout: LayoutLike = await (await request.get(`${API}/api/schedules/${scheduleId}/seats/layout`)).json();
  const status: { sections: { sectionId: number; seatCount: number; bitmap: string }[] } = await (
    await request.get(`${API}/api/schedules/${scheduleId}/seats/status`)
  ).json();

  const free: number[] = [];
  for (const section of status.sections) {
    const decoded = decodeSectionBitmap(decodeBase64(section.bitmap), section.seatCount);
    const seats = layout.sections.find((s) => s.id === section.sectionId)?.seats ?? [];
    seats.forEach((seat, i) => {
      if (decoded[i] === SEAT_STATUS.FREE) free.push(seat.id);
    });
  }
  const seatId = free[skip];
  if (seatId === undefined) throw new Error("빈 좌석이 없습니다");

  const geometry = layoutGeometry(layout);
  const seat = geometry.seats.find((s) => s.seatId === seatId)!;
  const section = layout.sections.find((s) => s.id === seat.sectionId)!;
  const info = section.seats.find((s) => s.id === seatId)!;

  // 캔버스는 화면 폭에 맞춰 축소된다 — 맵 좌표 → CSS 좌표. 아래쪽 좌석이면 먼저 스크롤해 보이게 한다
  const canvas = page.locator("canvas");
  await canvas.evaluate((el, y) => {
    const rect = el.getBoundingClientRect();
    const scale = rect.width / (el as HTMLCanvasElement).width * (window.devicePixelRatio || 1);
    window.scrollTo(0, Math.max(0, window.scrollY + rect.top + y * scale - window.innerHeight / 2));
  }, seat.y);
  const box = (await canvas.boundingBox())!;
  const scale = box.width / geometry.width;
  return {
    seatId,
    label: `${section.name}구역 ${info.rowNo}열 ${info.colNo}번`,
    x: box.x + (seat.x + geometry.seatSize / 2) * scale,
    y: box.y + (seat.y + geometry.seatSize / 2) * scale,
  };
}

/** 좌석을 탭하고 '좌석 선점'을 눌러 결제 화면까지 간다. 예매 id를 돌려준다 */
export async function holdAndGoToPay(page: Page, seat: FreeSeat): Promise<number> {
  await page.mouse.click(seat.x, seat.y);
  await expect(page.getByText(seat.label)).toBeVisible();
  await page.getByRole("button", { name: "좌석 선점" }).click();
  await page.waitForURL(/\/reservations\/\d+\/pay/);
  await expect(page.getByText("주문 정보")).toBeVisible();
  return Number(page.url().match(/reservations\/(\d+)/)![1]);
}

export async function confirmKeyOf(page: Page, reservationId: number): Promise<string | null> {
  return page.evaluate((k) => sessionStorage.getItem(k), `tr-confirm-key-${reservationId}`);
}
