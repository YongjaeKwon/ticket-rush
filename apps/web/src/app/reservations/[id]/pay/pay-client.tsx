"use client";

// 결제 화면. 예매는 URL의 id로 서버에서 다시 읽는다 — 새로고침·뒤로가기·탭 복제가 모두 같은 경로를 탄다.
// 핵심은 접수번호(Idempotency-Key)의 수명: 결과를 모르면 같은 키, 서버가 답을 줬으면 새 키 (lib/confirm-policy.ts).
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { HoldIsland } from "@/components/HoldIsland";
import { PAYMENT_METHODS, PaymentMethods, type PaymentMethodId } from "@/components/PaymentMethods";
import { ReservationLoadError } from "@/components/ReservationLoadError";
import { StatusCard } from "@/components/StatusCard";
import { Toast, type ToastMessage } from "@/components/Toast";
import { classifyConfirmFailure } from "@/lib/confirm-policy";
import { formatDateTime } from "@/lib/format";
import { BOOKING_FEE, TICKET_PRICE, TOTAL_PRICE, formatKrw } from "@/lib/pricing";
import {
  clearActiveHold,
  confirmReservation,
  currentConfirmKey,
  discardConfirmKey,
  getReservation,
  hasConfirmKey,
  saveConfirmResult,
} from "@/lib/reservation";
import { useCountdown } from "@/lib/use-countdown";
import { useReservationView } from "@/lib/use-reservation-view";

type Phase = "idle" | "paying" | "checking" | "leaving";

/** 결과를 모를 때 서버 상태를 다시 묻는 횟수·간격 — 먼저 보낸 요청이 서버에서 끝날 시간을 준다 */
const VERIFY_POLLS = 3;
const VERIFY_INTERVAL_MS = 1_000;

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const EXPIRED_TOAST: ToastMessage = {
  code: "HOLD_EXPIRED",
  sticky: true,
  text: "선점 시간이 지났어요. 좌석 선택으로 돌아가요.",
};
const UNKNOWN_TOAST: ToastMessage = {
  code: "RESULT_UNKNOWN",
  sticky: true,
  text: "결제 결과를 확인하지 못했어요. 다시 누르면 같은 접수번호로 이어서 확인해요. 두 번 결제되지 않아요.",
};

export function PayClient({ reservationId }: { reservationId: number }) {
  const router = useRouter();
  const view = useReservationView(reservationId);
  const reservation = view.reservation;
  const scheduleId = reservation?.scheduleId;
  const seatsHref = scheduleId ? `/schedules/${scheduleId}/seats` : "/";

  const [method, setMethod] = useState<PaymentMethodId>("card");
  const [agreed, setAgreed] = useState(true);
  const [phase, setPhase] = useState<Phase>("idle");
  const [toast, setToast] = useState<ToastMessage | null>(null);
  // 결과를 못 받은 시도가 남아 있으면(키가 남아 있다) 버튼이 "결제 결과 확인"이 되고 같은 키로 이어간다
  const [resuming, setResuming] = useState(false);
  const remain = useCountdown(reservation?.status === "HELD" ? reservation.expiresAt : undefined);

  // 화면을 떠난 뒤(뒤로가기 등)에는 진행 중이던 절차가 화면을 바꾸지 못하게 한다
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);
  // 만료 처리는 한 번만 — 확인에 실패해도 조회를 되풀이하지 않는다
  const expiryHandled = useRef(false);

  const dismissToast = useCallback(() => setToast(null), []);

  const goDone = useCallback(() => {
    if (!alive.current) return;
    setPhase("leaving");
    router.replace(`/reservations/${reservationId}/done`);
  }, [router, reservationId]);

  const goSeats = useCallback(
    (delayMs: number) => {
      if (!alive.current) return;
      setPhase("leaving");
      setTimeout(() => {
        if (alive.current) router.replace(seatsHref);
      }, delayMs);
    },
    [router, seatsHref],
  );

  // 이미 확정된 예매로 들어오면(뒤로가기 등) 완료 화면으로
  useEffect(() => {
    if (reservation?.status === "CONFIRMED") router.replace(`/reservations/${reservationId}/done`);
  }, [reservation?.status, router, reservationId]);

  /** 서버 상태를 다시 읽어 확정돼 있으면 완료로 보낸다 (부작용 없는 조회). */
  const settledAsConfirmed = useCallback(async () => {
    const latest = await getReservation(reservationId).catch(() => null);
    if (latest?.status !== "CONFIRMED") return false;
    discardConfirmKey(reservationId); // 시도는 끝났다 — 응답만 잃어버린 것
    goDone();
    return true;
  }, [reservationId, goDone]);

  /**
   * 결과를 모를 때(네트워크·타임아웃·5xx·새로고침): 돈이 나갔을 수 있으니 새 키를 만들지 않는다.
   * 먼저 부작용 없는 조회로 몇 초 확인하고, 그래도 모르면 같은 접수번호로 이어갈 버튼을 남긴다.
   * 몇 초를 기다리는 이유 — 먼저 보낸 요청이 서버에서 아직 처리 중일 수 있어서(서버는 같은 키의 동시 요청을 막지 않는다).
   */
  const verifyThenPrompt = useCallback(async () => {
    setPhase("checking");
    for (let i = 0; i < VERIFY_POLLS; i++) {
      await wait(VERIFY_INTERVAL_MS);
      if (!alive.current) return;
      if (await settledAsConfirmed()) return;
    }
    if (!alive.current) return;
    setResuming(true);
    setPhase("idle");
    setToast(UNKNOWN_TOAST);
  }, [settledAsConfirmed]);

  // 결과를 못 받은 시도가 남아 있으면(키가 남아 있다) 새로고침 직후에도 같은 절차로 이어간다
  useEffect(() => {
    if (hasConfirmKey(reservationId)) void verifyThenPrompt();
  }, [reservationId, verifyThenPrompt]);

  // 카운트다운이 끝나면 좌석 선택으로 — 단, 결제 요청 중이면 서버 판정을 기다린다.
  // 결과를 못 받은 시도가 남아 있으면 서버에 먼저 묻는다(그 시도가 만료 직전에 확정됐을 수 있다).
  useEffect(() => {
    if (remain !== 0 || phase !== "idle" || expiryHandled.current) return;
    expiryHandled.current = true;
    let cancelled = false;
    (async () => {
      if (hasConfirmKey(reservationId)) {
        setPhase("checking");
        const latest = await getReservation(reservationId).catch(() => null);
        if (cancelled || !alive.current) return;
        if (latest?.status === "CONFIRMED") {
          discardConfirmKey(reservationId);
          goDone();
          return;
        }
        if (latest === null) {
          // 서버에 못 물었다 — 키를 지키고, 사용자가 이어서 확인할 수 있게 둔다
          setResuming(true);
          setPhase("idle");
          setToast(UNKNOWN_TOAST);
          return;
        }
      }
      discardConfirmKey(reservationId);
      if (scheduleId) clearActiveHold(scheduleId);
      setToast(EXPIRED_TOAST);
      goSeats(1_200);
    })();
    return () => {
      cancelled = true;
    };
  }, [remain, phase, reservationId, scheduleId, goDone, goSeats]);

  /** 한 번의 결제 시도. 실패는 분류표(confirm-policy)대로 처리한다. */
  const attempt = useCallback(
    async (key: string): Promise<void> => {
      let failure;
      try {
        const result = await confirmReservation(reservationId, key);
        if (!alive.current) return;
        saveConfirmResult(reservationId, result);
        discardConfirmKey(reservationId);
        goDone();
        return;
      } catch (e) {
        failure = classifyConfirmFailure(e);
      }
      if (!alive.current) return;
      switch (failure.kind) {
        case "declined":
          // 같은 키면 거절 응답이 재생된다 — 다음 시도는 새 키
          discardConfirmKey(reservationId);
          setResuming(false);
          setPhase("idle");
          setToast({
            code: "PAYMENT_DECLINED",
            text: "카드사에서 결제를 거절했어요. 선점은 유지 중이에요. 다시 시도해 주세요.",
          });
          return;
        case "verify":
          // 만료·상태 충돌은 "잃어버린 성공"일 수도 있다(중복 탭, 커밋 뒤 끊김) — 상태를 한 번 더 본다
          if (await settledAsConfirmed()) return;
          if (!alive.current) return;
          discardConfirmKey(reservationId);
          if (scheduleId) clearActiveHold(scheduleId);
          setToast(
            failure.code === "SEAT_ALREADY_CONFIRMED"
              ? {
                  code: failure.code,
                  sticky: true,
                  text: "이 좌석은 이미 다른 예매로 확정됐어요. 다른 좌석을 골라 주세요.",
                }
              : { ...EXPIRED_TOAST, code: failure.code },
          );
          goSeats(1_500);
          return;
        case "gone":
          discardConfirmKey(reservationId);
          setPhase("idle");
          setToast({
            code: failure.code,
            sticky: true,
            text:
              failure.code === "RESERVATION_NOT_OWNED"
                ? "본인의 예매만 결제할 수 있어요."
                : "예매를 찾을 수 없어요.",
          });
          return;
        case "rejected":
          discardConfirmKey(reservationId);
          setResuming(false);
          setPhase("idle");
          setToast({ code: failure.code, text: "요청을 처리하지 못했어요. 다시 시도해 주세요." });
          return;
        case "server":
        case "unknown":
          // 5xx도 "결과 모름"으로 다룬다 — PG 승인 뒤에 서버가 넘어졌을 수 있어, 같은 키라도 바로 다시 보내지 않는다
          await verifyThenPrompt();
          return;
      }
    },
    [reservationId, scheduleId, goDone, goSeats, settledAsConfirmed, verifyThenPrompt],
  );

  const pay = useCallback(async () => {
    if (phase !== "idle") return;
    if (!agreed) {
      setToast({ text: "약관에 동의해 주세요." });
      return;
    }
    setToast(null);
    setPhase("paying");
    // 이어서 확인하는 경우: 먼저 보낸 요청이 그사이 끝났을 수 있으니 보내기 전에 한 번 더 조회한다
    if (resuming && (await settledAsConfirmed())) return;
    if (!alive.current) return;
    await attempt(currentConfirmKey(reservationId));
  }, [phase, agreed, resuming, attempt, settledAsConfirmed, reservationId]);

  if (view.loading) {
    return (
      <Shell>
        <p className="py-16 text-center text-sm text-sub">예매 정보를 불러오는 중…</p>
      </Shell>
    );
  }
  if (!reservation) {
    return (
      <Shell>
        <ReservationLoadError error={view.error} onRetry={() => void view.reload()} verb="결제" />
      </Shell>
    );
  }
  if (reservation.status !== "HELD" && reservation.status !== "CONFIRMED") {
    return (
      <Shell>
        <StatusCard
          title={reservation.status === "CANCELLED" ? "취소된 예매예요" : "선점 시간이 지났어요"}
          sub="좌석을 다시 선택해 주세요."
          href={seatsHref}
          cta="좌석 다시 선택"
        />
      </Shell>
    );
  }

  const busy = phase !== "idle";
  const ctaLabel =
    phase === "paying" ? "승인 요청 중…"
    : phase === "checking" ? "결제 결과 확인 중…"
    : phase === "leaving" ? "이동 중…"
    : resuming ? "결제 결과 확인"
    : `${formatKrw(TOTAL_PRICE)} 결제하기`;

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pb-44 pt-6">
      <nav className="flex items-center gap-2 pb-2">
        <Link href={seatsHref} aria-label="좌석 선택으로" className="-ml-1 rounded-full p-1.5 text-brand active:bg-brand/10">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round"><path d="M15 5l-7 7 7 7" /></svg>
        </Link>
        <span className="text-[17px] font-bold">결제</span>
        {remain !== null && (
          <span className="ml-auto">
            <HoldIsland remainSeconds={remain} />
          </span>
        )}
      </nav>

      <h2 className="px-1 pb-2.5 pt-4 text-[17px] font-bold tracking-tight">주문 정보</h2>
      <section className="rounded-card bg-surface px-4.5 py-1" style={{ boxShadow: "var(--shadow-card)" }}>
        <Row k="공연" v={view.schedule?.event?.title ?? "…"} />
        <Row k="일시" v={view.schedule?.startsAt ? formatDateTime(view.schedule.startsAt) : "…"} num />
        <Row k="좌석" v={view.seatLabel ?? "…"} />
        <Row k="티켓 금액" v={formatKrw(TICKET_PRICE)} num />
        <Row k="예매수수료" v={formatKrw(BOOKING_FEE)} num />
      </section>

      <h2 className="px-1 pb-2.5 pt-5 text-[17px] font-bold tracking-tight">결제 수단</h2>
      <PaymentMethods value={method} onChange={setMethod} disabled={busy} />
      <p className="px-1 pt-2.5 text-xs text-sub">{PAYMENT_METHODS.find((m) => m.id === method)?.note}</p>

      <section className="mt-4 rounded-card bg-surface" style={{ boxShadow: "var(--shadow-card)" }}>
        <label className="flex cursor-pointer items-center gap-2.5 px-4.5 pt-3.5 text-sm font-bold">
          <input
            type="checkbox"
            className="peer sr-only"
            checked={agreed}
            disabled={busy}
            onChange={(e) => setAgreed(e.target.checked)}
          />
          <span
            className={`flex h-[21px] w-[21px] flex-none items-center justify-center rounded-full border-[1.6px] transition-colors peer-focus-visible:ring-2 peer-focus-visible:ring-brand/40 ${
              agreed ? "border-brand bg-brand text-white" : ""
            }`}
            style={{ borderColor: agreed ? undefined : "var(--line-strong)" }}
          >
            {agreed && (
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.4" strokeLinecap="round" strokeLinejoin="round">
                <path d="M5 13l4.5 4.5L19 7" />
              </svg>
            )}
          </span>
          전체 동의
        </label>
        <p className="pb-3.5 pl-[50px] pr-4.5 pt-0.5 text-xs text-sub">
          주문 내용 확인 및 결제 진행 동의 · 취소/환불 규정 동의
        </p>
      </section>

      <div
        className="fixed inset-x-0 bottom-0 border-t px-4 pb-[max(16px,env(safe-area-inset-bottom))] pt-3 backdrop-blur-lg"
        style={{ background: "var(--glass)", borderColor: "var(--line)" }}
      >
        <div className="mx-auto max-w-md">
          <div className="flex items-baseline justify-between px-1.5 pb-2.5">
            <span className="text-sm font-semibold">총 결제 금액</span>
            <span className="text-[22px] font-extrabold tracking-tight tabular-nums">{formatKrw(TOTAL_PRICE)}</span>
          </div>
          <div
            className="mb-2.5 h-[3px] overflow-hidden rounded-[2px] transition-opacity"
            style={{ background: "var(--line)", opacity: busy ? 1 : 0 }}
          >
            <i
              className="block h-full w-1/3 bg-brand"
              style={{ animation: busy ? "progress-slide 1.1s linear infinite" : "none" }}
            />
          </div>
          <button
            onClick={() => void pay()}
            disabled={busy || reservation.status !== "HELD"}
            className="w-full rounded-cta bg-brand py-4 text-base font-bold text-white transition-transform active:scale-[.97] disabled:opacity-60"
            style={{ boxShadow: "var(--shadow-cta)" }}
          >
            {ctaLabel}
          </button>
        </div>
      </div>

      <Toast message={toast} onDone={dismissToast} />
    </main>
  );
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pt-6">
      <nav className="flex items-center pb-2">
        <span className="text-[17px] font-bold">결제</span>
      </nav>
      {children}
    </main>
  );
}

function Row({ k, v, num = false }: { k: string; v: string; num?: boolean }) {
  return (
    <div
      className="flex items-baseline justify-between gap-3 border-t py-3 text-sm first:border-t-0"
      style={{ borderColor: "var(--line)" }}
    >
      <span className="text-sub">{k}</span>
      <span className={`text-right font-semibold ${num ? "tabular-nums" : ""}`}>{v}</span>
    </div>
  );
}
