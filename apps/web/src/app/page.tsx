// 임시 홈 — 공연 목록 화면은 다음 단위(체크리스트 3번)에서 만든다.
// 여기서는 워크스페이스 배선(api-client 타입, 디자인 토큰)이 살아 있는지만 보여준다.
import type { paths } from "@ticket-rush/api-client";

type EventSummary =
  paths["/api/events"]["get"]["responses"]["200"]["content"]["*/*"][number];

export default function Home() {
  // 타입이 openapi.json에서 생성됐다는 증거 — 필드명을 틀리면 빌드가 깨진다
  const sample: EventSummary = {
    id: 1,
    title: "2026 TICKET RUSH LIVE",
    venue: "올림픽공원 체조경기장",
    openAt: "2026-09-07T11:00:00",
  };

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col gap-4 px-4 py-10">
      <h1 className="text-2xl font-extrabold tracking-tight">티켓러시</h1>
      <p className="text-sm text-sub">
        STAGE 2 기반 공사 완료 — 공연 목록 화면은 다음 단위에서 붙습니다.
      </p>
      <div
        className="rounded-card bg-surface p-5"
        style={{ boxShadow: "var(--shadow-card)" }}
      >
        <span className="text-xs font-semibold text-brand">api-client 타입 연결 확인</span>
        <p className="mt-1 text-base font-bold">{sample.title}</p>
        <p className="text-sm text-sub">{sample.venue}</p>
      </div>
    </main>
  );
}
