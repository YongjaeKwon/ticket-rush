// 공연 상세 — 서버 컴포넌트. 카운트다운만 클라이언트 컴포넌트다.
import Link from "next/link";
import { notFound } from "next/navigation";
import { ApiError } from "@ticket-rush/api-client";
import { getEvent } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { BOOKING_FEE, TICKET_PRICE, formatKrw } from "@/lib/pricing";
import { ArtPanel } from "@/components/ArtPanel";
import { OpenCountdown } from "@/components/OpenCountdown";

export default async function EventDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const event = await getEvent(Number(id)).catch((e) => {
    if (e instanceof ApiError && e.code === "EVENT_NOT_FOUND") notFound();
    throw e;
  });
  const schedule = event.schedules?.[0];

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pb-28 pt-6">
      <nav className="flex items-center gap-2 pb-4">
        <Link href="/" className="-ml-1 rounded-full p-1.5 text-brand active:bg-brand/10">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M15 5l-7 7 7 7" /></svg>
        </Link>
        <span className="text-[17px] font-bold">공연 상세</span>
      </nav>

      <div className="flex gap-4">
        <ArtPanel className="flex aspect-[3/4] w-[106px] flex-none flex-col justify-end rounded-cell p-3">
          <b className="text-sm font-extrabold leading-snug">{event.title}</b>
        </ArtPanel>
        <div className="flex flex-col gap-1.5 pt-1">
          <div className="flex gap-1.5">
            <span className="rounded-full bg-brand/10 px-2 py-0.5 text-[11px] font-bold text-brand">단독판매</span>
            <span className="rounded-full px-2 py-0.5 text-[11px] font-bold text-sub" style={{ background: "rgba(60,60,67,.08)" }}>1인 1매</span>
          </div>
          <h1 className="text-xl font-extrabold leading-snug tracking-tight">{event.title}</h1>
          <p className="text-[13px] text-sub">{event.venue}</p>
          {schedule?.startsAt && (
            <p className="text-[13px] text-sub">{formatDateTime(schedule.startsAt)} · 120분</p>
          )}
        </div>
      </div>

      <h2 className="px-1 pb-2.5 pt-6 text-[17px] font-bold tracking-tight">회차 선택</h2>
      <div
        className="flex items-center justify-between rounded-card bg-surface px-4 py-3.5"
        style={{ boxShadow: "var(--shadow-card)" }}
      >
        <div>
          <p className="text-[15px] font-bold tabular-nums">
            {schedule?.startsAt ? formatDateTime(schedule.startsAt) : "회차 정보 없음"}
          </p>
          <p className="text-xs text-sub">전 구역 2,000석 · 전석 {formatKrw(TICKET_PRICE)}</p>
        </div>
        <span className="flex h-[22px] w-[22px] flex-none items-center justify-center rounded-full bg-brand text-white">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round"><path d="M5 13l4.5 4.5L19 7" /></svg>
        </span>
      </div>

      {event.openAt && (
        <ArtPanel className="mt-3.5 rounded-card p-5">
          <OpenCountdown openAt={event.openAt} />
        </ArtPanel>
      )}

      <details
        className="mt-3.5 overflow-hidden rounded-card bg-surface"
        style={{ boxShadow: "var(--shadow-card)" }}
      >
        <summary className="cursor-pointer px-4 py-3.5 text-sm font-semibold">
          예매 유의사항
        </summary>
        <div className="px-4 pb-4 text-xs leading-relaxed text-sub">
          · 예매 오픈 직후 접속이 몰리면 대기열이 적용됩니다.
          <br />· 좌석 선점 후 5분 안에 결제하지 않으면 자동 취소됩니다.
          <br />· 같은 좌석은 두 번 결제되지 않습니다. 예매수수료 {formatKrw(BOOKING_FEE)}이 부과됩니다.
        </div>
      </details>

      <div
        className="fixed inset-x-0 bottom-0 border-t px-4 pb-[max(16px,env(safe-area-inset-bottom))] pt-3 backdrop-blur-lg"
        style={{ background: "var(--glass)", borderColor: "var(--line)" }}
      >
        <div className="mx-auto max-w-md">
          <Link
            href={schedule ? `/schedules/${schedule.id}/queue` : "#"}
            className="block w-full rounded-cta bg-brand py-4 text-center text-base font-bold text-white transition-transform active:scale-[.97]"
            style={{ boxShadow: "var(--shadow-cta)" }}
          >
            예매하기
          </Link>
        </div>
      </div>
    </main>
  );
}
