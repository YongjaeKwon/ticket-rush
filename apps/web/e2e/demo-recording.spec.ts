// README GIF 녹화용 — 검증이 아니라 "보여주기"라 화면마다 잠깐 멈춘다.
// E2E_VIDEO=1일 때만 돈다: E2E_VIDEO=1 pnpm -F web exec playwright test demo-recording
import { expect, test } from "@playwright/test";
import { findFreeSeat, useFreshUser } from "./helpers";

test.skip(!process.env.E2E_VIDEO, "녹화 전용 — E2E_VIDEO=1 로 실행");

test("데모 녹화: 목록 → 상세 → 대기열 → 좌석 → 결제 → 완료", async ({ page, request }) => {
  const pause = (ms = 1_400) => page.waitForTimeout(ms);
  await useFreshUser(page);

  await page.goto("/");
  await pause(1_800);
  await page.getByRole("link").filter({ hasText: "2026 TICKET RUSH LIVE" }).first().click();
  await expect(page.getByRole("heading", { name: "2026 TICKET RUSH LIVE" })).toBeVisible();
  await pause();

  await page.getByRole("link", { name: "예매하기" }).click();
  await expect(page).toHaveURL(/\/queue/);
  await page.waitForURL(/\/seats/, { timeout: 30_000 });
  await expect(page.locator("canvas")).toBeVisible();
  await pause();

  const seat = await findFreeSeat(page, request, { skip: 7 });
  await page.mouse.click(seat.x, seat.y);
  await expect(page.getByText(seat.label)).toBeVisible();
  await pause(1_000);
  await page.getByRole("button", { name: "좌석 선점" }).click();
  await expect(page).toHaveURL(/\/pay/);
  await expect(page.getByText("주문 정보")).toBeVisible();
  await pause(1_600);

  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page).toHaveURL(/\/done/);
  await expect(page.getByText("예매가 완료되었습니다")).toBeVisible();
  await pause(2_600);
});
