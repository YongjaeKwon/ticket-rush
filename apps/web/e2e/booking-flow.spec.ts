// 예매 한 흐름 — 목록 → 상세 → 대기열 → 좌석 선점 → 결제 → 완료(월렛 패스)
import { expect, test } from "@playwright/test";
import { findFreeSeat, useFreshUser, waitForSeatCanvas } from "./helpers";

test("공연 목록에서 시작해 티켓을 받는다", async ({ page, request }) => {
  await useFreshUser(page);

  // 목록 → 상세 (서버 렌더링)
  await page.goto("/");
  await page.getByRole("link").filter({ hasText: "2026 TICKET RUSH LIVE" }).first().click();
  await expect(page).toHaveURL(/\/events\/1/);
  await expect(page.getByRole("heading", { name: "2026 TICKET RUSH LIVE" })).toBeVisible();

  // 예매하기 → 대기열 → 입장되면 좌석맵으로
  await page.getByRole("link", { name: "예매하기" }).click();
  await expect(page).toHaveURL(/\/schedules\/1\/queue/);
  await page.waitForURL(/\/schedules\/1\/seats/, { timeout: 30_000 });
  await waitForSeatCanvas(page);

  // 좌석 탭 → 선점 → 결제 화면 (카운트다운·좌석·금액)
  const seat = await findFreeSeat(page, request);
  await page.mouse.click(seat.x, seat.y);
  await expect(page.getByText(seat.label)).toBeVisible();
  await page.getByRole("button", { name: "좌석 선점" }).click();
  await expect(page).toHaveURL(/\/reservations\/\d+\/pay/);
  await expect(page.getByText("선점 중")).toBeVisible();
  await expect(page.getByText(seat.label)).toBeVisible();
  await expect(page.getByText("134,000원 결제하기")).toBeVisible();

  // 결제 → 판정 대기(202 접수 뒤 조회 폴링) → 완료 (월렛 패스에 같은 좌석과 예매번호)
  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page).toHaveURL(/\/reservations\/\d+\/done/, { timeout: 20_000 });
  await expect(page.getByText("예매가 완료되었습니다")).toBeVisible();
  const pass = page.getByTestId("wallet-pass");
  await expect(pass).toContainText(seat.label);
  await expect(pass).toContainText(/TR-\d{4}-\d{6}/);
  await expect(pass).toContainText("134,000원");
  // 승인번호는 조회 응답에서 온다 — 202 전환 뒤 확정 응답에는 더 이상 없어 조용히 사라지기 쉬운 칸이다
  await expect(pass).toContainText("승인 번호");
  await expect(pass).toContainText(/mock-[0-9a-f-]{36}/);
});
