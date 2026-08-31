// 공연 목록 — 서버 컴포넌트. 첫 화면은 서버가 그린다(LCP).
import Link from "next/link";
import { getEvents } from "@/lib/api";
import { formatDateTime, dDay } from "@/lib/format";
import { ArtPanel, SeatSig } from "@/components/ArtPanel";

export default async function HomePage() {
  const events = await getEvents();
  const [featured, ...upcoming] = events;

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pb-16 pt-8">
      <header className="flex items-baseline justify-between px-1 pb-4">
        <h1 className="text-[22px] font-extrabold tracking-tight">지금 예매</h1>
        <span className="text-xs text-sub">놓치면 안 되는 오픈, 한 곳에서</span>
      </header>

      {featured && (
        <Link
          href={`/events/${featured.id}`}
          className="block overflow-hidden rounded-card bg-surface transition-transform active:scale-[.98]"
          style={{ boxShadow: "var(--shadow-card)" }}
        >
          <ArtPanel className="flex aspect-[16/8] flex-col justify-end p-5">
            <div className="mb-auto">
              <SeatSig />
            </div>
            <span className="text-xs font-semibold text-white/55">단독 공연</span>
            <p className="mt-1 text-[26px] font-extrabold leading-tight tracking-tight">
              {featured.title}
            </p>
            <p className="mt-1.5 text-[13px] text-white/60">{featured.venue}</p>
          </ArtPanel>
          <div className="flex items-center justify-between px-4 py-3.5">
            <div>
              <p className="text-sm font-bold">
                {featured.openAt ? `${formatDateTime(featured.openAt)} 예매 오픈` : ""}
              </p>
              <p className="text-xs text-sub">전석 132,000원 · 1인 1매</p>
            </div>
            {featured.openAt && dDay(featured.openAt) >= 0 && (
              <span className="rounded-full bg-danger-bg px-2.5 py-1 text-[11px] font-bold text-danger">
                오픈 D-{dDay(featured.openAt) || "DAY"}
              </span>
            )}
          </div>
        </Link>
      )}

      {upcoming.length > 0 && (
        <>
          <h2 className="px-1 pb-2.5 pt-6 text-[17px] font-bold tracking-tight">
            오픈 예정
          </h2>
          <div className="rounded-card bg-surface" style={{ boxShadow: "var(--shadow-card)" }}>
            {upcoming.map((event) => (
              <Link
                key={event.id}
                href={`/events/${event.id}`}
                className="flex items-center gap-3 border-b px-4 py-3.5 last:border-b-0"
                style={{ borderColor: "var(--line)" }}
              >
                <div className="flex-1">
                  <p className="text-sm font-semibold">{event.title}</p>
                  <p className="text-xs text-sub">
                    {event.openAt ? `${formatDateTime(event.openAt)} 오픈` : ""} ·{" "}
                    {event.venue}
                  </p>
                </div>
                <span className="text-sub">›</span>
              </Link>
            ))}
          </div>
        </>
      )}

      <p
        className="mt-4 rounded-cell px-4 py-3 text-xs leading-relaxed text-sub"
        style={{ background: "rgba(60,60,67,.05)" }}
      >
        <b className="font-semibold text-ink">안내</b> · 계정당 1매만 예매할 수 있는
        선착순 예매 서비스입니다.
      </p>
    </main>
  );
}
