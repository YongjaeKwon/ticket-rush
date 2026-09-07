"use client";

// 완료 화면 — 월렛 패스. 예매·회차·좌석은 서버에서 다시 읽는다.
// PG 승인번호는 확정 응답에만 있어(조회 API에 없음) 같은 탭에서 넘어왔을 때만 보여준다.
import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { ArtPanel, SeatSig } from "@/components/ArtPanel";
import { ReservationLoadError } from "@/components/ReservationLoadError";
import { StatusCard } from "@/components/StatusCard";
import { formatDateTime, parseUtc } from "@/lib/format";
import { TOTAL_PRICE, formatKrw } from "@/lib/pricing";
import { clearActiveHold, discardConfirmKey, readConfirmResult } from "@/lib/reservation";
import { useReservationView } from "@/lib/use-reservation-view";

/**
 * 예매 번호·바코드 캡션 — 디자인 규격(TR-2026-001284 / TR20260907001284).
 * 서버 id와 공연 날짜(KST)에서 결정적으로 만들어 어느 기기에서 열어도 같은 티켓이 된다.
 */
function ticketNumbers(reservationId: number, startsAt: string) {
  const kst = new Date(parseUtc(startsAt).getTime() + 9 * 3600_000);
  const yyyy = String(kst.getUTCFullYear());
  const mm = String(kst.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(kst.getUTCDate()).padStart(2, "0");
  const serial = String(reservationId).padStart(6, "0");
  return { bookingNo: `TR-${yyyy}-${serial}`, barcodeNo: `TR${yyyy}${mm}${dd}${serial}` };
}

/** 바코드 막대 — reservationId를 시드로 한 결정적 난수. 재렌더·재방문에도 같은 모양 */
function barcodeBars(seed: number): { width: number; on: boolean }[] {
  let s = Math.imul(seed, 0x9e3779b1) >>> 0 || 1;
  const next = () => {
    s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
    return s / 4294967296;
  };
  const widths = [1, 1, 1, 2, 2, 3];
  return Array.from({ length: 46 }, () => ({
    width: widths[Math.floor(next() * widths.length)] * 2,
    on: next() > 0.32,
  }));
}

export function DoneClient({ reservationId }: { reservationId: number }) {
  const router = useRouter();
  const view = useReservationView(reservationId);
  const reservation = view.reservation;
  const scheduleId = reservation?.scheduleId;
  const [paymentTx, setPaymentTx] = useState<string | null>(null);

  useEffect(() => {
    setPaymentTx(readConfirmResult(reservationId)?.paymentTransactionId ?? null);
  }, [reservationId]);

  // 아직 결제 전이면(URL 직접 진입) 결제 화면으로
  useEffect(() => {
    if (reservation?.status === "HELD") router.replace(`/reservations/${reservationId}/pay`);
  }, [reservation?.status, router, reservationId]);

  // 확인 — 이 예매에 얽힌 브라우저 기억(활성 홀드·접수번호)을 정리하고 처음으로. replace라 뒤로가기로 결제 화면에 돌아가지 않는다
  const finish = useCallback(() => {
    if (scheduleId) clearActiveHold(scheduleId);
    discardConfirmKey(reservationId);
    router.replace("/");
  }, [scheduleId, reservationId, router]);

  const bars = useMemo(() => barcodeBars(reservationId), [reservationId]);
  // 좌석 시그니처 도트는 seatId 비트에서 — 좌석마다 다른 무늬
  const seatId = reservation?.seatId ?? 0;
  const seatSig = Array.from({ length: 7 }, (_, i) => (seatId >> i) & 1);

  if (view.loading) {
    return (
      <Shell>
        <p className="py-16 text-center text-sm text-sub">티켓을 불러오는 중…</p>
      </Shell>
    );
  }
  if (!reservation) {
    return (
      <Shell>
        <ReservationLoadError error={view.error} onRetry={() => void view.reload()} verb="확인" />
      </Shell>
    );
  }
  if (reservation.status !== "CONFIRMED") {
    return (
      <Shell>
        <StatusCard
          title={reservation.status === "CANCELLED" ? "취소된 예매예요" : "선점 시간이 지나 확정되지 않았어요"}
          sub="좌석을 다시 선택해 주세요."
          href={scheduleId ? `/schedules/${scheduleId}/seats` : "/"}
          cta="좌석 다시 선택"
        />
      </Shell>
    );
  }

  const startsAt = view.schedule?.startsAt;
  const numbers = startsAt ? ticketNumbers(reservationId, startsAt) : null;

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pb-28 pt-6">
      <nav className="flex items-center pb-2">
        <span className="text-[17px] font-bold">예매 완료</span>
      </nav>

      <div
        className="mx-auto mt-4 flex h-[58px] w-[58px] items-center justify-center rounded-full bg-brand text-white motion-safe:animate-[pop_.5s_var(--spring)]"
        style={{ boxShadow: "var(--shadow-cta)" }}
      >
        <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
          <path d="M5 13l4.5 4.5L19 7" />
        </svg>
      </div>
      <h1 className="mt-3 text-center text-[21px] font-extrabold tracking-tight">예매가 완료되었습니다</h1>
      <p className="mt-1 text-center text-[13px] text-sub">내 티켓에서 언제든 확인할 수 있어요</p>

      <section
        data-testid="wallet-pass"
        className="mt-4 overflow-hidden rounded-pass bg-surface"
        style={{ boxShadow: "var(--shadow-card)" }}
      >
        <ArtPanel className="px-5 pb-4 pt-4.5">
          <div className="flex items-center justify-between text-xs font-bold text-white/55">
            <span>티켓러시 · 모바일 티켓</span>
            <SeatSig pattern={seatSig} />
          </div>
          <p className="mt-2.5 text-xl font-extrabold tracking-tight">{view.schedule?.event?.title ?? " "}</p>
          <p className="mt-0.5 text-xs text-white/55">{view.schedule?.event?.venue ?? " "}</p>
        </ArtPanel>

        <div className="grid grid-cols-2 gap-x-2.5 gap-y-3.5 px-5 pb-4 pt-4.5">
          <Field label="일시" value={startsAt ? formatDateTime(startsAt) : "…"} num />
          <Field label="좌석" value={view.seatLabel ?? "…"} />
          <Field label="결제 금액" value={formatKrw(TOTAL_PRICE)} num />
          <Field label="예매 번호" value={numbers?.bookingNo ?? "…"} mono />
          {paymentTx && <Field label="승인 번호" value={paymentTx} mono wide />}
        </div>

        {/* 절취선 — 양끝 노치는 바탕색 원 */}
        <div className="relative mx-3.5 border-t-2 border-dashed" style={{ borderColor: "var(--line)" }}>
          <i className="absolute -left-[26px] -top-[11px] h-[22px] w-[22px] rounded-full bg-bg" />
          <i className="absolute -right-[26px] -top-[11px] h-[22px] w-[22px] rounded-full bg-bg" />
        </div>

        <div aria-hidden className="mx-6 mb-1.5 mt-4 flex h-[46px] items-stretch gap-[2px]">
          {bars.map((bar, i) => (
            <i key={i} className="block" style={{ width: bar.width, background: bar.on ? "var(--ink)" : "transparent" }} />
          ))}
        </div>
        <p className="pb-4.5 text-center font-mono text-[11px] tracking-[.26em] text-sub">{numbers?.barcodeNo ?? " "}</p>
      </section>

      <p className="mt-3.5 rounded-cell px-4 py-3 text-xs leading-relaxed text-sub" style={{ background: "rgba(60,60,67,.05)" }}>
        <b className="font-semibold text-ink">입장 QR은 공연 당일 활성화됩니다.</b> 같은 좌석은 두 번 확정되지 않아요 — 이
        티켓이 유일합니다.
      </p>

      <div
        className="fixed inset-x-0 bottom-0 border-t px-4 pb-[max(16px,env(safe-area-inset-bottom))] pt-3 backdrop-blur-lg"
        style={{ background: "var(--glass)", borderColor: "var(--line)" }}
      >
        <div className="mx-auto max-w-md">
          <button
            type="button"
            onClick={finish}
            className="block w-full rounded-cta bg-brand py-4 text-center text-base font-bold text-white transition-transform active:scale-[.97]"
            style={{ boxShadow: "var(--shadow-cta)" }}
          >
            확인
          </button>
        </div>
      </div>
    </main>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pt-6">
      <nav className="flex items-center pb-2">
        <span className="text-[17px] font-bold">예매 완료</span>
      </nav>
      {children}
    </main>
  );
}

function Field({
  label,
  value,
  num = false,
  mono = false,
  wide = false,
}: {
  label: string;
  value: string;
  num?: boolean;
  mono?: boolean;
  wide?: boolean;
}) {
  return (
    <div className={wide ? "col-span-2" : ""}>
      <p className="text-[10px] font-bold tracking-[.1em] text-sub">{label}</p>
      <p className={`mt-0.5 font-bold tracking-tight ${mono ? "font-mono text-[13px]" : "text-[15px]"} ${num ? "tabular-nums" : ""}`}>
        {value}
      </p>
    </div>
  );
}
