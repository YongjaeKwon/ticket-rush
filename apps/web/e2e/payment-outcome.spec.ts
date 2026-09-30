// 확정 202 접수 뒤 "판정을 기다리는" 결제 화면의 갈래들 — ADR 0009.
// 서버 판정은 조회 응답을 흉내 내 결정적으로 만든다. 화면이 읽는 필드(status·paymentStatus)는 실제 계약과 같고,
// 실제 결제 왕복은 백엔드 ConfirmFlowIntegrationTest·PaymentDeclinedIntegrationTest가 커버한다.
import { expect, test, type Page } from "@playwright/test";
import { confirmKeyOf, enterSeatMap, findFreeSeat, holdAndGoToPay, useFreshUser } from "./helpers";

type Verdict = { status: string; paymentStatus: string | null; expiresAt?: string };

/** 이 예매의 조회 응답을 n번째 호출마다 원하는 판정으로 바꾼다 — 실제 응답을 받아 두 칸만 덮는다 */
async function stubReservation(page: Page, reservationId: number, verdictAt: (call: number) => Verdict) {
  let calls = 0;
  await page.route(`**/api/reservations/${reservationId}`, async (route) => {
    calls += 1;
    const response = await route.fetch();
    route.fulfill({ response, json: { ...(await response.json()), ...verdictAt(calls) } });
  });
  return () => calls;
}

async function acceptConfirm(page: Page, reservationId: number) {
  await page.route("**/api/reservations/*/confirm", (route) =>
    route.fulfill({ status: 202, json: { reservationId, status: "HELD", paymentStatus: "REQUESTED" } }),
  );
}

const REQUESTED: Verdict = { status: "HELD", paymentStatus: "REQUESTED" };

test("다른 탭이 결제 중이면(409) 이 시도의 번호를 버리고 그 판정을 기다린다", async ({ page, request }) => {
  await useFreshUser(page);
  await enterSeatMap(page);
  const reservationId = await holdAndGoToPay(page, await findFreeSeat(page, request));

  const sentKeys: string[] = [];
  page.on("request", (r) => {
    if (/\/confirm$/.test(r.url())) sentKeys.push(r.headers()["idempotency-key"]);
  });
  await page.route("**/api/reservations/*/confirm", (route) =>
    route.fulfill({
      status: 409,
      contentType: "application/problem+json",
      json: { title: "PAYMENT_IN_PROGRESS", status: 409, code: "PAYMENT_IN_PROGRESS", detail: "결제가 진행 중입니다" },
    }),
  );
  await stubReservation(page, reservationId, (call) =>
    call <= 2 ? REQUESTED : { status: "CONFIRMED", paymentStatus: "APPROVED" },
  );

  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page).toHaveURL(/\/done/, { timeout: 15_000 });
  expect(sentKeys).toHaveLength(1); // 새 번호로 결제를 또 요청하지 않는다
  expect(await confirmKeyOf(page, reservationId)).toBeNull();
});

test("다른 탭의 결제에 합류해 판정을 함께 기다리고, 판정이 나면 다시 합류하지 않는다", async ({ page, request }) => {
  await useFreshUser(page);
  await enterSeatMap(page);
  const reservationId = await holdAndGoToPay(page, await findFreeSeat(page, request));

  // 새로고침 뒤 2.5초 동안은 결제 중, 그다음부터 거절 — 이 탭은 결제를 누른 적이 없다(키 없음).
  // 호출 횟수가 아니라 시각으로 가른다 — 개발 서버(StrictMode)는 첫 조회를 두 번 부른다
  let declineFrom = Number.POSITIVE_INFINITY;
  const calls = await stubReservation(page, reservationId, () =>
    Date.now() < declineFrom ? REQUESTED : { status: "HELD", paymentStatus: "DECLINED" },
  );
  declineFrom = Date.now() + 2_500;
  await page.reload();
  await expect(page.getByRole("button", { name: "결제 승인 대기 중…" })).toBeVisible();
  await expect(page.getByRole("status")).toContainText("PAYMENT_DECLINED");
  await expect(page.getByRole("button", { name: /결제하기/ })).toBeEnabled();

  // 낡은 스냅샷(REQUESTED)으로 다시 합류하면 조회가 계속 늘어난다
  const settled = calls();
  await page.waitForTimeout(2_500);
  expect(calls()).toBe(settled);
});

test("판정이 20초를 넘기면 번호를 지키고 '결제 결과 확인'을 남긴다", async ({ page, request }) => {
  await useFreshUser(page);
  await enterSeatMap(page);
  const reservationId = await holdAndGoToPay(page, await findFreeSeat(page, request));
  await acceptConfirm(page, reservationId);
  await stubReservation(page, reservationId, () => REQUESTED);

  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page.getByRole("status")).toContainText("PAYMENT_PENDING", { timeout: 30_000 });
  await expect(page.getByRole("button", { name: "결제 결과 확인" })).toBeEnabled();
  expect(await confirmKeyOf(page, reservationId)).not.toBeNull(); // 판정 전이니 번호를 버리지 않는다
});

test("결제가 시스템 오류로 실패하면(FAILED) 전용 문구와 함께 좌석 선택으로 돌아간다", async ({ page, request }) => {
  await useFreshUser(page);
  await enterSeatMap(page);
  const reservationId = await holdAndGoToPay(page, await findFreeSeat(page, request));
  await acceptConfirm(page, reservationId);
  await stubReservation(page, reservationId, (call) =>
    call <= 1 ? REQUESTED : { status: "EXPIRED", paymentStatus: "FAILED" },
  );

  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page.getByRole("status")).toContainText("결제 처리 중 오류가 생겨 선점이 풀렸어요");
  await expect(page).toHaveURL(/\/seats/, { timeout: 10_000 });
  expect(await confirmKeyOf(page, reservationId)).toBeNull();

  // 완료 화면도 5분 만료와 다른 문구로 안내한다
  await page.goto(`/reservations/${reservationId}/done`);
  await expect(page.getByText("결제 처리 중 오류로 확정되지 않았어요")).toBeVisible();
});

test("판정을 기다리다 상한에 걸린 뒤 선점이 만료되면, 서버에 먼저 물어 확정을 발견한다", async ({ page, request }) => {
  test.setTimeout(90_000);
  await useFreshUser(page);
  await enterSeatMap(page);
  const reservationId = await holdAndGoToPay(page, await findFreeSeat(page, request));

  // 다른 탭의 결제가 끝나지 않은 채 선점이 22초 뒤 만료되고, 만료 순간 서버는 이미 확정해 둔 상태.
  // 만료 처리가 스스로를 취소해 "확인 중"에 멈추거나, 묻지 않고 "만료"로 끝내면 이 테스트가 깨진다
  const expireAt = Date.now() + 22_000;
  const expiresAt = new Date(expireAt).toISOString().replace("Z", ""); // 서버처럼 UTC LocalDateTime
  await stubReservation(page, reservationId, () =>
    Date.now() < expireAt
      ? { ...REQUESTED, expiresAt }
      : { status: "CONFIRMED", paymentStatus: "APPROVED", expiresAt },
  );
  await page.reload();
  await expect(page.getByRole("button", { name: "결제 승인 대기 중…" })).toBeVisible();
  await expect(page.getByRole("status")).toContainText("PAYMENT_PENDING", { timeout: 30_000 });
  await expect(page).toHaveURL(/\/done/, { timeout: 30_000 });
  await expect(page.getByText("예매가 완료되었습니다")).toBeVisible();
});
