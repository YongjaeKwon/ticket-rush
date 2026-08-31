"use client";

// 대기열 화면 — SSE로 순번을 받다가, 끊기면 2초 폴링으로 내려앉는다.
// 입장되면 입장권을 저장하고 좌석 선택으로 이동한다.
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { getUserId, saveAdmissionToken } from "@/lib/user";

const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
const RING_CIRCUMFERENCE = 2 * Math.PI * 54;

type Status = { position: number; admitted: boolean; token: string | null };

export function QueueClient({ scheduleId }: { scheduleId: number }) {
  const router = useRouter();
  const [status, setStatus] = useState<Status | null>(null);
  const [transport, setTransport] = useState<"sse" | "polling">("sse");
  const [entering, setEntering] = useState(true);
  // 진행률 계산용 — 처음 받은 순번을 전체 길이로 삼는다
  const initialPosition = useRef<number | null>(null);

  useEffect(() => {
    const userId = getUserId();
    let eventSource: EventSource | null = null;
    let pollTimer: ReturnType<typeof setInterval> | null = null;
    let closed = false;

    const handle = (next: Status) => {
      if (closed) return;
      if (initialPosition.current === null && next.position > 0) {
        initialPosition.current = next.position;
      }
      setStatus(next);
      if (next.admitted && next.token) {
        closed = true;
        eventSource?.close();
        if (pollTimer) clearInterval(pollTimer);
        saveAdmissionToken(scheduleId, next.token);
        router.replace(`/schedules/${scheduleId}/seats`);
      }
    };

    const startPolling = () => {
      setTransport("polling");
      pollTimer = setInterval(async () => {
        try {
          const res = await fetch(
            `${API}/api/schedules/${scheduleId}/queue/me`,
            { headers: { "X-User-Id": userId } },
          );
          if (res.ok) handle(await res.json());
        } catch {
          // 다음 주기에 재시도
        }
      }, 2_000);
    };

    const startStream = () => {
      eventSource = new EventSource(
        `${API}/api/schedules/${scheduleId}/queue/stream?userId=${encodeURIComponent(userId)}`,
      );
      eventSource.addEventListener("queue-status", (e) =>
        handle(JSON.parse((e as MessageEvent).data)),
      );
      eventSource.onerror = () => {
        // 입장 직후의 정상 종료도 error로 온다 — 이동 중이 아니면 폴링으로 폴백
        eventSource?.close();
        if (!closed) startPolling();
      };
    };

    (async () => {
      await fetch(`${API}/api/schedules/${scheduleId}/queue`, {
        method: "POST",
        headers: { "X-User-Id": userId },
      }).catch(() => {});
      setEntering(false);
      startStream();
    })();

    return () => {
      closed = true;
      eventSource?.close();
      if (pollTimer) clearInterval(pollTimer);
    };
  }, [scheduleId, router]);

  const position = status?.position ?? 0;
  const total = initialPosition.current ?? Math.max(position, 1);
  const progress = position > 0 ? Math.max(0.02, 1 - position / total) : 0.02;
  const etaMinutes = Math.max(1, Math.ceil(position / 100));

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pt-6">
      <nav className="flex items-center gap-2 pb-4">
        <span className="text-[17px] font-bold">대기열</span>
        <span className="ml-auto text-[11px] text-sub">
          {transport === "sse" ? "실시간 연결" : "연결 재시도 중 · 폴링"}
        </span>
      </nav>

      <section
        className="mt-3 rounded-card bg-surface px-5 py-7 text-center"
        style={{ boxShadow: "var(--shadow-card)" }}
      >
        <p className="text-[13px] font-semibold text-sub">나의 대기 순서</p>

        <div className="relative mx-auto mt-4 h-[190px] w-[190px]">
          <svg viewBox="0 0 120 120" className="h-full w-full -rotate-90">
            <circle cx="60" cy="60" r="54" fill="none" strokeWidth="11"
              stroke="rgba(60,60,67,.09)" />
            <circle cx="60" cy="60" r="54" fill="none" strokeWidth="11"
              stroke="var(--brand)" strokeLinecap="round"
              strokeDasharray={RING_CIRCUMFERENCE}
              strokeDashoffset={RING_CIRCUMFERENCE * (1 - progress)}
              style={{ transition: "stroke-dashoffset .6s var(--ease)" }} />
          </svg>
          <div className="absolute inset-0 flex flex-col items-center justify-center">
            <span className="text-[40px] font-extrabold leading-tight tracking-tight tabular-nums">
              {entering || status === null ? "—" : position.toLocaleString()}
            </span>
            <span className="text-xs font-semibold text-sub">번째</span>
          </div>
        </div>

        <div className="mt-3 flex justify-center gap-2 text-xs font-semibold text-sub">
          <span className="rounded-full px-3.5 py-1.5" style={{ background: "rgba(60,60,67,.06)" }}>
            앞에 <b className="text-ink tabular-nums">{position.toLocaleString()}</b>명
          </span>
          <span className="rounded-full px-3.5 py-1.5" style={{ background: "rgba(60,60,67,.06)" }}>
            예상 <b className="text-ink">약 {etaMinutes}분</b>
          </span>
        </div>
      </section>

      <p
        className="mt-3 rounded-cell px-4 py-3 text-xs leading-relaxed text-sub"
        style={{ background: "rgba(60,60,67,.05)" }}
      >
        <b className="font-semibold text-ink">잠시만 기다려 주세요</b> · 접속 인원이 많아
        순서대로 입장하고 있습니다. 새로고침해도 순서는 유지되며, 순서가 되면 자동으로
        좌석 선택으로 이동합니다.
      </p>
    </main>
  );
}
