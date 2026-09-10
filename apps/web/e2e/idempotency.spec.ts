// 결제 접수번호(Idempotency-Key)의 수명 — ADR 0006을 브라우저에서 확인한다.
// "결과를 모르면 같은 번호, 서버가 판정했으면 새 번호"
import { expect, test } from "@playwright/test";
import { API, confirmKeyOf, enterSeatMap, findFreeSeat, holdAndGoToPay, useFreshUser } from "./helpers";

test("요청이 서버에 닿기 전에 끊기면 같은 접수번호로 다시 보내 이어간다", async ({ page, request }) => {
  const userId = await useFreshUser(page);
  await enterSeatMap(page);
  const reservationId = await holdAndGoToPay(page, await findFreeSeat(page, request));

  // 확정 요청을 브라우저 단계에서 끊는다 — 서버는 아무것도 받지 못했고, 브라우저는 결과를 모른다
  const sentKeys: string[] = [];
  page.on("request", (r) => {
    if (/\/confirm$/.test(r.url())) sentKeys.push(r.headers()["idempotency-key"]);
  });
  await page.route("**/api/reservations/*/confirm", (route) => route.abort("connectionfailed"));
  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page.getByText("결제 결과 확인 중…")).toBeVisible();
  await expect(page.getByRole("status")).toContainText("RESULT_UNKNOWN");
  const keptKey = await confirmKeyOf(page, reservationId);
  expect(keptKey).not.toBeNull();
  await expect(page.getByRole("button", { name: "결제 결과 확인" })).toBeEnabled();

  // 연결이 돌아오면 같은 접수번호로 이어간다
  await page.unroute("**/api/reservations/*/confirm");
  await page.getByRole("button", { name: "결제 결과 확인" }).click();
  await expect(page).toHaveURL(/\/done/);
  expect(sentKeys).toHaveLength(2);
  expect(sentKeys[1]).toBe(sentKeys[0]);
  expect(await confirmKeyOf(page, reservationId)).toBeNull(); // 시도가 끝났으니 번호를 버린다

  // 서버에도 확정은 하나
  const latest = await (await request.get(`${API}/api/reservations/${reservationId}`, { headers: { "X-User-Id": userId } })).json();
  expect(latest.status).toBe("CONFIRMED");
});

test("서버는 결제를 끝냈는데 응답만 잃으면, 재전송 없이 조회로 완료를 확인한다", async ({ page, request }) => {
  const userId = await useFreshUser(page);
  await enterSeatMap(page);
  const reservationId = await holdAndGoToPay(page, await findFreeSeat(page, request));

  // 요청은 서버까지 보내고(확정 완료) 응답만 버린다 — 돈은 나갔는데 브라우저는 모르는 상황
  const sentKeys: string[] = [];
  page.on("request", (r) => {
    if (/\/confirm$/.test(r.url())) sentKeys.push(r.headers()["idempotency-key"]);
  });
  await page.route("**/api/reservations/*/confirm", async (route) => {
    await route.fetch(); // 서버 처리는 그대로
    await route.abort("connectionfailed"); // 응답만 유실
  });
  await page.getByRole("button", { name: /결제하기/ }).click();

  // 화면은 부작용 없는 조회로 확정을 발견하고, 같은 번호로도 다시 보내지 않는다
  await expect(page).toHaveURL(/\/done/, { timeout: 15_000 });
  await expect(page.getByText("예매가 완료되었습니다")).toBeVisible();
  expect(sentKeys).toHaveLength(1);
  expect(await confirmKeyOf(page, reservationId)).toBeNull();

  const latest = await (await request.get(`${API}/api/reservations/${reservationId}`, { headers: { "X-User-Id": userId } })).json();
  expect(latest.status).toBe("CONFIRMED");
});

test("PG가 거절하면 접수번호를 버리고 새 번호로 다시 시도한다", async ({ page, request }) => {
  await useFreshUser(page);
  await enterSeatMap(page);
  const reservationId = await holdAndGoToPay(page, await findFreeSeat(page, request));

  const sentKeys: string[] = [];
  page.on("request", (r) => {
    if (/\/confirm$/.test(r.url())) sentKeys.push(r.headers()["idempotency-key"]);
  });
  // 서버의 거절 응답(402 PAYMENT_DECLINED)을 흉내 낸다 — 화면이 읽는 필드(status·code·detail)는 실제 계약과 동일.
  // 여기서 검증하는 건 클라이언트 정책(번호 폐기 → 새 번호)이고, 서버의 "4xx 저장·재생" 성질은 백엔드 통합 테스트가 커버한다
  await page.route("**/api/reservations/*/confirm", (route) =>
    route.fulfill({
      status: 402,
      contentType: "application/problem+json",
      body: JSON.stringify({ title: "PAYMENT_DECLINED", status: 402, detail: "결제가 거절됐습니다", code: "PAYMENT_DECLINED" }),
    }),
  );
  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page.getByRole("status")).toContainText("PAYMENT_DECLINED");
  await expect(page.getByRole("status")).toContainText("카드사에서 결제를 거절했어요");
  expect(await confirmKeyOf(page, reservationId)).toBeNull(); // 같은 번호면 거절만 재생되므로 버린다
  await expect(page.getByText("선점 중")).toBeVisible(); // 홀드는 살아 있다

  await page.unroute("**/api/reservations/*/confirm");
  await page.getByRole("button", { name: /결제하기/ }).click();
  await expect(page).toHaveURL(/\/done/);
  expect(sentKeys).toHaveLength(2);
  expect(sentKeys[1]).not.toBe(sentKeys[0]);
});

test("결제 화면에서 뒤로가기해도 내 홀드가 좌석맵에 복원된다", async ({ page, request }) => {
  await useFreshUser(page);
  await enterSeatMap(page);
  const seat = await findFreeSeat(page, request);
  const reservationId = await holdAndGoToPay(page, seat);

  await page.goBack();
  await expect(page).toHaveURL(/\/seats/);
  await expect(page.getByText("선점 중")).toBeVisible();
  await expect(page.getByText(seat.label)).toBeVisible();
  await page.getByRole("button", { name: "결제하기" }).click();
  await expect(page).toHaveURL(new RegExp(`/reservations/${reservationId}/pay`));
});
