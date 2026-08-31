// 얇은 fetch 래퍼 — baseURL과 에러 형식(RFC 9457 Problem Details)만 안다.
// 어떤 경로를 어떤 타입으로 부를지는 사용하는 쪽(웹/앱)이 생성 타입으로 정한다.

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

const BASE_URL = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, init);
  if (!res.ok) {
    // 백엔드는 에러를 problem+json으로 내려준다 — code 필드가 기계용 식별자
    const problem = await res.json().catch(() => null);
    throw new ApiError(
      res.status,
      problem?.code ?? "UNKNOWN",
      problem?.detail ?? `요청 실패: ${res.status}`,
    );
  }
  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}
