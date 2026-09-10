import { defineConfig, devices } from "@playwright/test";

// E2E — 백엔드는 미리 떠 있어야 한다 (로컬: ./gradlew bootRun, CI: compose infra + bootRun).
// 웹 서버는 여기서 띄운다 (로컬은 이미 떠 있는 dev 서버를 재사용, CI는 build 결과를 start).
// 백엔드가 8080이 아니면 테스트와 dev 서버 둘 다에 NEXT_PUBLIC_API_BASE를 export하고 실행한다.
export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  // 시나리오들이 같은 회차의 좌석 상태를 공유한다 — 순서대로 하나씩
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:3000",
    // 모바일 웹이 1차 대상 — 안드로이드 크롬 에뮬레이션(터치·뷰포트·UA)
    ...devices["Pixel 7"],
    trace: "retain-on-failure",
    // README GIF 녹화용: E2E_VIDEO=1 pnpm -F web exec playwright test demo-recording
    video: process.env.E2E_VIDEO ? "on" : "off",
  },
  webServer: {
    command: process.env.CI ? "pnpm start" : "pnpm dev",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
