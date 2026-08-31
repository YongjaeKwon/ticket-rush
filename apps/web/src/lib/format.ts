// 시각 표기 유틸 — 백엔드는 UTC LocalDateTime 문자열을 준다.
const DAYS = ["일", "월", "화", "수", "목", "금", "토"];

export function parseUtc(value: string): Date {
  // "2026-09-07T11:00:00" (UTC, 오프셋 없음) → Z를 붙여 확정
  return new Date(value.endsWith("Z") ? value : `${value}Z`);
}

/** "9.7 (일) 11:00" — 한국 티켓팅 표기 */
export function formatDateTime(value: string): string {
  const d = parseUtc(value);
  const kst = new Date(d.getTime() + 9 * 3600_000);
  return `${kst.getUTCMonth() + 1}.${kst.getUTCDate()} (${DAYS[kst.getUTCDay()]}) ${String(
    kst.getUTCHours(),
  ).padStart(2, "0")}:${String(kst.getUTCMinutes()).padStart(2, "0")}`;
}

/** 오픈까지 남은 일수. 지났으면 음수. */
export function dDay(value: string): number {
  return Math.ceil((parseUtc(value).getTime() - Date.now()) / 86_400_000);
}
