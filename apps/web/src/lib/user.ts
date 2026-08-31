// 로그인은 범위 밖(X-User-Id 단순화) — 브라우저마다 익명 id를 만들어 유지한다.
const KEY = "tr-user-id";

export function getUserId(): string {
  if (typeof window === "undefined") {
    throw new Error("getUserId는 클라이언트에서만 부른다");
  }
  let id = localStorage.getItem(KEY);
  if (!id) {
    id = `web-${crypto.randomUUID().slice(0, 13)}`;
    localStorage.setItem(KEY, id);
  }
  return id;
}

/** 입장권은 새로고침에도 살아야 하지만 탭을 닫으면 버려도 된다. */
export function saveAdmissionToken(scheduleId: number, token: string) {
  sessionStorage.setItem(`tr-admission-${scheduleId}`, token);
}

export function getAdmissionToken(scheduleId: number): string | null {
  return sessionStorage.getItem(`tr-admission-${scheduleId}`);
}
