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
import { classifyConfirmFailure, classifyPaymentOutcome, resumeAction, type Verdict } from "@/lib/confirm-policy";
import { formatDateTime } from "@/lib/format";
import { BOOKING_FEE, TICKET_PRICE, TOTAL_PRICE, formatKrw } from "@/lib/pricing";
import {
  clearActiveHold,
  confirmReservation,
  currentConfirmKey,
  discardConfirmKey,
  getReservation,
  hasConfirmKey,
  isConfirmAccepted,
  markConfirmAccepted,
} from "@/lib/reservation";
import { useCountdown } from "@/lib/use-countdown";
import { useReservationView } from "@/lib/use-reservation-view";

type Phase = "idle" | "paying" | "waiting" | "checking" | "leaving";

/**
 * 접수(202) 뒤 판정을 기다리는 간격·상한. 판정은 보통 2~4초
 * (서랍 릴레이 1초 × 두 번 + Kafka + PG) — 넘기면 같은 키로 이어서 확인할 버튼을 남긴다.
 */
const WAIT_INTERVAL_MS = 1_000;
const WAIT_LIMIT_MS = 20_000;

/** 결과를 모를 때 서버 상태를 다시 묻는 횟수·간격 — 먼저 보낸 요청이 서버에 닿을 시간을 준다 */
const VERIFY_POLLS = 3;
const VERIFY_INTERVAL_MS = 1_000;

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const EXPIRED_TOAST: ToastMessage = {
  code: "HOLD_EXPIRED",
  sticky: true,
  text: "선점 시간이 지났어요. 좌석 선택으로 돌아가요.",
};
const FAILED_TOAST: ToastMessage = {
  code: "PAYMENT_FAILED",
  sticky: true,
  text: "결제 처리 중 오류가 생겨 선점이 풀렸어요. 좌석을 다시 선택해 주세요.",
};
const LOST_TOAST: ToastMessage = {
  code: "SEAT_NOT_CONFIRMED",
  sticky: true,
  text: "결제는 승인됐지만 좌석을 확정하지 못했어요(선점 만료 또는 이미 확정된 좌석). 좌석을 다시 선택해 주세요.",
};
const DECLINED_TOAST: ToastMessage = {
  code: "PAYMENT_DECLINED",
  text: "카드사에서 결제를 거절했어요. 선점은 유지 중이에요. 다시 시도해 주세요.",
};
const SLOW_TOAST: ToastMessage = {
  code: "PAYMENT_PENDING",
  sticky: true,
  text: "결제 승인이 늦어지고 있어요. 잠시 후 '결제 결과 확인'을 눌러 주세요. 두 번 결제되지 않아요.",
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
  // 판정을 못 본 시도가 남아 있으면 버튼이 "결제 결과 확인"이 되고 새 결제 대신 그 시도를 이어서 확인한다
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
  // 만료 처리·새로고침 재개·남의 결제 합류는 한 번만
  const expiryHandled = useRef(false);
  const resumed = useRef(false);
  const joinedPending = useRef(false);

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

  /** 판정이 난 결과를 화면 이동으로 바꾼다 — 판정이 났으니 이 시도의 키는 끝났다. */
  const settle = useCallback(
    (outcome: Verdict) => {
      if (!alive.current) return;
      discardConfirmKey(reservationId);
      setResuming(false);
      if (outcome === "confirmed") {
        goDone();
        return;
      }
      if (outcome === "declined") {
        setPhase("idle");
        setToast(DECLINED_TOAST);
        return;
      }
      if (scheduleId) clearActiveHold(scheduleId);
      setToast(outcome === "failed" ? FAILED_TOAST : outcome === "lost" ? LOST_TOAST : EXPIRED_TOAST);
      goSeats(1_500);
    },
    [reservationId, scheduleId, goDone, goSeats],
  );

  /**
   * 접수(202) 뒤 판정을 기다린다 — 부작용 없는 조회를 1초마다. 이 사이에 키를 버리면 안 된다:
   * 새 키로 다시 누르면 결제 요청 쪽지가 한 장 더 나간다(서버가 PAYMENT_IN_PROGRESS로 막지만 여기서도 지킨다).
   */
  const waitForOutcome = useCallback(async () => {
    // 이 탭이 서버 판정을 직접 보고 있다 — 낡은 예매 스냅샷(REQUESTED)으로 다시 합류하지 않는다
    joinedPending.current = true;
    setPhase("waiting");
    const deadline = Date.now() + WAIT_LIMIT_MS;
    while (alive.current) {
      const latest = await getReservation(reservationId).catch(() => null);
      if (!alive.current) return;
      const outcome = latest ? classifyPaymentOutcome(latest) : "pending";
      if (outcome !== "pending" && outcome !== "open") {
        settle(outcome);
        return;
      }
      if (Date.now() >= deadline) {
        setResuming(true);
        setPhase("idle");
        setToast(SLOW_TOAST);
        return;
      }
      await wait(WAIT_INTERVAL_MS);
    }
  }, [reservationId, settle]);

  /**
   * 결과를 모를 때(네트워크·타임아웃·5xx·새로고침): 요청이 서버에 닿았는지부터 조회로 확인한다.
   * 결제 중이면 판정을 기다리고, 판정이 났으면 따른다. 끝내 모르면 같은 접수번호로 이어갈 버튼을 남긴다 —
   * 서버에 닿았으면 저장된 202가 재생되고, 안 닿았으면 그때 처음 처리돼 어느 쪽이든 쪽지는 한 장이다.
   */
  const verifyThenPrompt = useCallback(async () => {
    setPhase("checking");
    for (let i = 0; i < VERIFY_POLLS; i++) {
      await wait(VERIFY_INTERVAL_MS);
      if (!alive.current) return;
      const latest = await getReservation(reservationId).catch(() => null);
      if (!alive.current) return;
      if (!latest) continue;
      const outcome = classifyPaymentOutcome(latest);
      if (outcome === "pending") {
        await waitForOutcome();
        return;
      }
      if (outcome === "confirmed" || outcome === "failed" || outcome === "ended" || outcome === "lost") {
        settle(outcome);
        return;
      }
      if (outcome === "declined" && isConfirmAccepted(reservationId)) {
        settle("declined"); // 202를 받은 시도다 — 이 거절은 이 시도의 것이다
        return;
      }
      // declined·open — 이 시도가 닿았는지 모른다(거절이 이전 시도의 것일 수도). 같은 키 재전송이 가려 준다
    }
    if (!alive.current) return;
    setResuming(true);
    setPhase("idle");
    setToast(UNKNOWN_TOAST);
  }, [reservationId, settle, waitForOutcome]);

  // 판정을 못 본 시도가 남아 있으면(키가 남아 있다) 새로고침 직후에도 같은 절차로 이어간다.
  // 예매를 읽은 뒤에 한 번만 시작한다 — 먼저 시작하면 scheduleId 없는 settle을 쥔 루프가 하나 더 돈다
  useEffect(() => {
    if (resumed.current || scheduleId === undefined || !hasConfirmKey(reservationId)) return;
    resumed.current = true;
    void verifyThenPrompt();
  }, [scheduleId, reservationId, verifyThenPrompt]);

  // 다른 탭(또는 떠났다 돌아온 이 탭)이 보낸 결제가 진행 중이면 — 새 결제 대신 그 판정을 함께 기다린다
  useEffect(() => {
    if (joinedPending.current || phase !== "idle" || hasConfirmKey(reservationId)) return;
    if (reservation?.status !== "HELD" || reservation.paymentStatus !== "REQUESTED") return;
    joinedPending.current = true;
    void waitForOutcome();
  }, [reservation?.status, reservation?.paymentStatus, phase, reservationId, waitForOutcome]);

  // 카운트다운이 끝나면 좌석 선택으로 — 단, 결제 요청 중이면 서버 판정을 기다린다.
  // 판정을 못 본 시도가 남아 있으면 서버에 먼저 묻는다(그 시도가 만료 직전에 확정됐을 수 있다).
  // 재진입은 expiryHandled가 막는다 — 정리 함수로 취소하면 자기 setPhase("checking")에 스스로 취소돼 멈춘다.
  useEffect(() => {
    if (remain !== 0 || phase !== "idle" || expiryHandled.current) return;
    expiryHandled.current = true;
    (async () => {
      // 이 탭의 시도가 있거나(키) 남의 결제를 기다리다 상한에 걸렸으면(resuming) 서버에 먼저 묻는다
      if (hasConfirmKey(reservationId) || resuming) {
        setPhase("checking");
        const latest = await getReservation(reservationId).catch(() => null);
        if (!alive.current) return;
        if (latest === null) {
          // 서버에 못 물었다 — 키를 지키고, 사용자가 이어서 확인할 수 있게 둔다
          setResuming(true);
          setPhase("idle");
          setToast(UNKNOWN_TOAST);
          return;
        }
        const outcome = classifyPaymentOutcome(latest);
        if (outcome === "confirmed" || outcome === "failed" || outcome === "lost") {
          settle(outcome);
          return;
        }
      }
      settle("ended");
    })();
  }, [remain, phase, reservationId, resuming, settle]);

  /** 한 번의 결제 시도. 접수(202)되면 판정을 기다리고, 실패는 분류표(confirm-policy)대로 처리한다. */
  const attempt = useCallback(
    async (key: string): Promise<void> => {
      let failure;
      try {
        await confirmReservation(reservationId, key); // 202 접수 — 판정이 아니다. 키는 판정이 날 때까지 지킨다
      } catch (e) {
        failure = classifyConfirmFailure(e);
      }
      if (!alive.current) return;
      if (!failure) {
        markConfirmAccepted(reservationId);
        await waitForOutcome();
        return;
      }
      switch (failure.kind) {
        case "inProgress":
          // 다른 시도(다른 탭·잃어버린 응답)가 결제 중 — 이 시도는 거부됐으니 키를 버리고 그 판정을 기다린다
          discardConfirmKey(reservationId);
          await waitForOutcome();
          return;
        case "verify": {
          // 만료·상태 충돌은 "잃어버린 성공"일 수도 있다(중복 탭, 커밋 뒤 끊김) — 상태를 한 번 더 본다
          const latest = await getReservation(reservationId).catch(() => null);
          if (!alive.current) return;
          const outcome = latest ? classifyPaymentOutcome(latest) : "ended";
          settle(outcome === "confirmed" || outcome === "failed" || outcome === "lost" ? outcome : "ended");
          return;
        }
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
          // 5xx도 "결과 모름"으로 다룬다 — 커밋 뒤에 넘어졌을 수 있어, 같은 키라도 바로 다시 보내지 않는다
          await verifyThenPrompt();
          return;
      }
    },
    [reservationId, settle, waitForOutcome, verifyThenPrompt],
  );

  const pay = useCallback(async () => {
    if (phase !== "idle") return;
    if (!agreed) {
      setToast({ text: "약관에 동의해 주세요." });
      return;
    }
    setToast(null);
    setPhase("paying");
    if (resuming) {
      // 이어서 확인: 먼저 보낸 시도가 그사이 판정됐을 수 있으니 보내기 전에 한 번 더 조회한다
      const latest = await getReservation(reservationId).catch(() => null);
      if (!alive.current) return;
      const action = resumeAction(
        latest ? classifyPaymentOutcome(latest) : null,
        hasConfirmKey(reservationId),
        isConfirmAccepted(reservationId),
      );
      switch (action.kind) {
        case "wait":
          await waitForOutcome();
          return;
        case "settle":
          settle(action.verdict);
          return;
        case "stay":
          setPhase("idle");
          setToast(SLOW_TOAST);
          return;
        case "reset":
          setResuming(false);
          break;
        case "resend":
          break;
      }
    }
    await attempt(currentConfirmKey(reservationId));
  }, [phase, agreed, resuming, reservationId, attempt, settle, waitForOutcome]);

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
          title={
            reservation.status === "CANCELLED" ? "취소된 예매예요"
            : reservation.paymentStatus === "FAILED" ? "결제 처리 중 오류로 선점이 풀렸어요"
            : reservation.paymentStatus === "APPROVED" ? "좌석을 확정하지 못했어요"
            : "선점 시간이 지났어요"
          }
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
    : phase === "waiting" ? "결제 승인 대기 중…"
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
