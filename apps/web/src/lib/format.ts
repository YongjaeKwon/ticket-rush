// 시각 표기 유틸 — 백엔드는 UTC LocalDateTime 문자열을 준다.
const DAYS = ["일", "월", "화", "수", "목", "금", "토"];

export function parseUtc(value: string): Date {
  // "2026-09-07T11:00:00" (UTC, 오프셋 없음) → Z를 붙여 확정.
  // 자바 LocalDateTime은 소수초를 6자리까지 내보내는데 ECMAScript 날짜 형식은 3자리까지만 보장한다 → 잘라낸다
  const normalized = value.replace(/(\.\d{3})\d+/, "$1");
  return new Date(normalized.endsWith("Z") ? normalized : `${normalized}Z`);
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
