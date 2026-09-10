import { configDefaults, defineConfig } from "vitest/config";

// 단위 테스트는 src 아래만 — e2e/*.spec.ts는 Playwright가 돌린다.
// 워커는 스레드로: 자식 프로세스 생성이 막히는 환경(보안 프로그램·메모리 부족)에서도 돈다.
export default defineConfig({
  test: {
    exclude: [...configDefaults.exclude, "e2e/**"],
    pool: "threads",
  },
});
