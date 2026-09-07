"use client";

// 좌석맵 — 2,000석을 DOM 없이 Canvas 하나에 그린다.
// 계산(좌표·디코딩·히트 테스트)은 전부 seat-map-core, 여기는 그리기와 상호작용만.
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ApiError } from "@ticket-rush/api-client";
import {
  SEAT_STATUS,
  decodeBase64,
  decodeSectionBitmap,
  hitTest,
  layoutGeometry,
  type LayoutLike,
  type MapGeometry,
} from "@ticket-rush/seat-map-core";
import { HoldIsland } from "@/components/HoldIsland";
import { getAdmissionToken } from "@/lib/user";
import {
  clearActiveHold,
  getReservation,
  holdSeat as requestHold,
  loadActiveHold,
  saveActiveHold,
} from "@/lib/reservation";
import { parseUtc } from "@/lib/format";
import { TICKET_PRICE, formatKrw } from "@/lib/pricing";

const API = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";
const HOLD_TTL_MS = 5 * 60_000;

const COLORS: Record<number, string> = {
  [SEAT_STATUS.FREE]: "#D6E1FF",
  [SEAT_STATUS.HELD]: "#E3E5E9",
  [SEAT_STATUS.CONFIRMED]: "#C9CDD4",
};
const COLOR_MINE = "#2E5BFF";

type SeatInfo = { sectionName: string; rowNo: number; colNo: number };

/**
 * 홀드 상태. optimistic=true는 서버 응답을 아직 못 받은 "낙관적" 표시 —
 * 탭한 즉시 '선점 요청 중' 캡슐을 띄우고(시간은 서버 응답 뒤에만), 실패하면 통째로 되돌린다.
 */
type Hold = { reservationId: number | null; expiresAtMs: number; optimistic: boolean };

export function SeatMapClient({ scheduleId }: { scheduleId: number }) {
  const router = useRouter();
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [layout, setLayout] = useState<LayoutLike | null>(null);
  const [statuses, setStatuses] = useState<Map<number, number>>(new Map());
  const [selected, setSelected] = useState<number | null>(null);
  const [held, setHeld] = useState<Hold | null>(null);
  const [remainSeconds, setRemainSeconds] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [needsAdmission, setNeedsAdmission] = useState(false);
  // 홀드 접수번호 — 좌석을 고를 때 새로 만들고, 연결이 끊겨 결과를 모르면 같은 키로 다시 보낸다
  const holdKeyRef = useRef<string | null>(null);

  const geometry: MapGeometry | null = useMemo(
    () => (layout ? layoutGeometry(layout) : null),
    [layout],
  );

  /** seatId → 사람이 읽는 좌석 정보 (선택 바 표기용) */
  const seatInfo = useMemo(() => {
    const map = new Map<number, SeatInfo>();
    layout?.sections.forEach((section) =>
      section.seats.forEach((seat) =>
        map.set(seat.id, { sectionName: section.name, rowNo: seat.rowNo, colNo: seat.colNo }),
      ),
    );
    return map;
  }, [layout]);

  // 배치 로드 + 상태 스트림 구독 (스냅샷 → 변경분)
  useEffect(() => {
    if (getAdmissionToken(scheduleId) === null) {
      setNeedsAdmission(true);
      return;
    }
    let eventSource: EventSource | null = null;
    let cancelled = false;

    (async () => {
      const res = await fetch(`${API}/api/schedules/${scheduleId}/seats/layout`);
      const layoutData: LayoutLike = await res.json();
      if (cancelled) return;
      setLayout(layoutData);

      eventSource = new EventSource(`${API}/api/schedules/${scheduleId}/seats/stream`);
      eventSource.addEventListener("seat-snapshot", (e) => {
        const sections: { sectionId: number; seatCount: number; bitmap: string }[] =
          JSON.parse((e as MessageEvent).data);
        const next = new Map<number, number>();
        for (const section of sections) {
          const decoded = decodeSectionBitmap(decodeBase64(section.bitmap), section.seatCount);
          const seats = layoutData.sections.find((s) => s.id === section.sectionId)?.seats ?? [];
          seats.forEach((seat, i) => next.set(seat.id, decoded[i]));
        }
        setStatuses(next);
      });
      eventSource.addEventListener("seat-status", (e) => {
        const { changes }: { changes: { seatId: number; status: number }[] } =
          JSON.parse((e as MessageEvent).data);
        setStatuses((prev) => {
          const next = new Map(prev);
          for (const change of changes) next.set(change.seatId, change.status);
          return next;
        });
      });
    })();

    return () => {
      cancelled = true;
      eventSource?.close();
    };
  }, [scheduleId]);

  // 뒤로가기·새로고침으로 돌아온 경우 — 내 홀드가 살아 있으면 복원한다 (서버에 확인한 뒤)
  useEffect(() => {
    const saved = loadActiveHold(scheduleId);
    if (!saved) return;
    let cancelled = false;
    (async () => {
      let latest;
      try {
        latest = await getReservation(saved.reservationId);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof ApiError) clearActiveHold(scheduleId); // 없는·남의 예매 — 잊는다
        return; // 서버에 못 물었으면 기억을 지키고 다음 마운트에서 다시 확인한다
      }
      if (cancelled) return;
      const expiresAtMs = latest.expiresAt ? parseUtc(latest.expiresAt).getTime() : 0;
      if (latest.status === "HELD" && expiresAtMs > Date.now()) {
        setSelected(saved.seatId);
        setHeld({ reservationId: saved.reservationId, expiresAtMs, optimistic: false });
      } else {
        clearActiveHold(scheduleId); // 확정·만료·취소된 홀드는 잊는다
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [scheduleId]);

  // 홀드 카운트다운 — 서버가 준 expiresAt이 진실. 낙관적 단계에선 시간을 보여주지 않는다
  useEffect(() => {
    if (!held) return;
    const tick = () => {
      const remain = Math.max(0, Math.ceil((held.expiresAtMs - Date.now()) / 1000));
      setRemainSeconds(remain);
      if (remain === 0 && !held.optimistic) {
        clearActiveHold(scheduleId);
        setHeld(null);
        setSelected(null);
        setNotice("선점 시간이 지났어요. 좌석을 다시 선택해 주세요.");
      }
    };
    tick();
    const timer = setInterval(tick, 500);
    return () => clearInterval(timer);
  }, [held, scheduleId]);

  // 그리기 — 상태·선택이 바뀔 때마다 전체를 다시 그린다 (2,000 사각형 ≈ 1ms)
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !geometry) return;
    const dpr = window.devicePixelRatio || 1;
    canvas.width = geometry.width * dpr;
    canvas.height = geometry.height * dpr;
    const ctx = canvas.getContext("2d")!;
    ctx.scale(dpr, dpr);
    ctx.clearRect(0, 0, geometry.width, geometry.height);

    ctx.font = "700 13px Pretendard, sans-serif";
    ctx.fillStyle = "#8A8F98";
    for (const section of geometry.sections) {
      ctx.fillText(`${section.name}구역`, section.x, section.y + 14);
    }

    const size = geometry.seatSize;
    for (const seat of geometry.seats) {
      const status = statuses.get(seat.seatId) ?? SEAT_STATUS.FREE;
      if (status === SEAT_STATUS.BLOCKED) continue;
      ctx.fillStyle = seat.seatId === selected ? COLOR_MINE : COLORS[status];
      ctx.beginPath();
      ctx.roundRect(seat.x, seat.y, size, size, 6);
      ctx.fill();
    }
  }, [geometry, statuses, selected]);

  const onCanvasClick = useCallback(
    (e: React.MouseEvent<HTMLCanvasElement>) => {
      if (!geometry || held) return;
      const rect = e.currentTarget.getBoundingClientRect();
      const scale = geometry.width / rect.width; // CSS 축소 배율 → 맵 좌표로 환산
      const seat = hitTest(
        geometry,
        (e.clientX - rect.left) * scale,
        (e.clientY - rect.top) * scale,
      );
      if (!seat) return;
      const status = statuses.get(seat.seatId) ?? SEAT_STATUS.FREE;
      if (status !== SEAT_STATUS.FREE) {
        setNotice("이미 선점된 좌석이에요. 다른 좌석을 선택해 주세요.");
        return;
      }
      setNotice(null);
      // 좌석이 바뀌면 새 접수번호 (본문이 달라 같은 키는 IDEMPOTENCY_CONFLICT). 같은 좌석을 다시 탭하면 키를 지킨다
      if (seat.seatId !== selected) holdKeyRef.current = null;
      setSelected(seat.seatId);
    },
    [geometry, statuses, held, selected],
  );

  const goPay = useCallback(() => {
    if (held?.reservationId) router.push(`/reservations/${held.reservationId}/pay`);
  }, [held, router]);

  /**
   * 좌석 선점 — 낙관적 UI.
   * 1) 탭한 즉시 "선점 요청 중" 캡슐을 띄우고 버튼을 잠근다(서버 응답 전).
   * 2) 성공하면 서버의 만료 시각으로 카운트다운을 시작하고 결제 화면으로 간다.
   * 3) 실패하면 캡슐을 내리고, 남이 잡은 좌석이면 즉시 회색으로 칠한다(SSE보다 먼저).
   */
  const holdSeat = useCallback(async () => {
    if (selected === null || held) return;
    const seatId = selected;
    const token = getAdmissionToken(scheduleId);
    if (token === null) {
      setNeedsAdmission(true);
      return;
    }
    setNotice(null);
    setHeld({ reservationId: null, expiresAtMs: Date.now() + HOLD_TTL_MS, optimistic: true });
    const key = holdKeyRef.current ?? (holdKeyRef.current = crypto.randomUUID());
    try {
      const result = await requestHold(scheduleId, seatId, token, key);
      saveActiveHold(scheduleId, { reservationId: result.reservationId, seatId, expiresAt: result.expiresAt });
      setHeld({
        reservationId: result.reservationId,
        expiresAtMs: parseUtc(result.expiresAt).getTime(),
        optimistic: false,
      });
      router.push(`/reservations/${result.reservationId}/pay`);
    } catch (e) {
      setHeld(null); // 롤백 — 카운트다운을 내린다
      const code = e instanceof ApiError ? e.code : null;
      if (e instanceof ApiError) holdKeyRef.current = null; // 서버가 답했으면 다음 시도는 새 키
      if (code === "ADMISSION_REQUIRED") {
        setNeedsAdmission(true);
        return;
      }
      if (code === "SEAT_ALREADY_HELD") {
        setStatuses((prev) => new Map(prev).set(seatId, SEAT_STATUS.HELD));
        setNotice("한발 늦었어요 — 방금 다른 분이 선점했습니다.");
      } else if (e instanceof ApiError) {
        setNotice(e.message);
      } else {
        // 결과를 모른다 — 좌석 선택은 유지하고, 다시 누르면 같은 키로 보낸다(성공했었다면 그 응답이 재생된다)
        setNotice("연결이 불안정해요. 다시 시도해 주세요.");
        return;
      }
      setSelected(null);
    }
  }, [selected, held, scheduleId, router]);

  if (needsAdmission) {
    return (
      <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pt-6">
        <span className="text-[17px] font-bold">좌석 선택</span>
        <section className="mt-4 rounded-card bg-surface p-5 text-center" style={{ boxShadow: "var(--shadow-card)" }}>
          <p className="text-sm font-bold text-danger">입장권이 없습니다</p>
          <p className="mt-1 text-xs text-sub">대기열을 먼저 통과해야 좌석을 고를 수 있어요.</p>
          <Link href={`/schedules/${scheduleId}/queue`}
            className="mt-4 inline-block rounded-cta bg-brand px-6 py-3 text-sm font-bold text-white">
            대기열 입장하기
          </Link>
        </section>
      </main>
    );
  }

  const info = selected !== null ? seatInfo.get(selected) : null;

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pb-32 pt-6">
      <nav className="flex items-center gap-2 pb-2">
        <span className="text-[17px] font-bold">좌석 선택</span>
        {held && remainSeconds !== null && (
          <span className="ml-auto">
            <HoldIsland remainSeconds={remainSeconds} pending={held.optimistic} />
          </span>
        )}
      </nav>

      <div className="mx-auto my-2 w-3/5 rounded-full py-1.5 text-center text-[10px] font-bold tracking-[.24em] text-sub"
        style={{ background: "rgba(60,60,67,.07)" }}>
        STAGE
      </div>

      <div className="rounded-card bg-surface p-3.5" style={{ boxShadow: "var(--shadow-card)" }}>
        {geometry ? (
          <canvas
            ref={canvasRef}
            onClick={onCanvasClick}
            style={{ width: "100%", height: "auto", display: "block", touchAction: "manipulation" }}
          />
        ) : (
          <p className="py-16 text-center text-sm text-sub">좌석 배치를 불러오는 중…</p>
        )}
      </div>

      <div className="flex justify-center gap-4 py-2.5 text-[11px] text-sub">
        <span className="flex items-center gap-1.5"><i className="h-2.5 w-2.5 rounded-[3px] bg-[#D6E1FF]" />선택 가능</span>
        <span className="flex items-center gap-1.5"><i className="h-2.5 w-2.5 rounded-[3px] bg-brand" />내 선택</span>
        <span className="flex items-center gap-1.5"><i className="h-2.5 w-2.5 rounded-[3px] bg-[#C9CDD4]" />선점·판매완료</span>
      </div>

      {notice && (
        <p className="rounded-cell px-4 py-3 text-xs font-medium text-danger" style={{ background: "var(--danger-bg)" }}>
          {notice}
        </p>
      )}

      {selected !== null && info && (
        <div className="fixed inset-x-0 bottom-0 border-t px-4 pb-[max(16px,env(safe-area-inset-bottom))] pt-3 backdrop-blur-lg"
          style={{ background: "var(--glass)", borderColor: "var(--line)" }}>
          <div className="mx-auto flex max-w-md items-center gap-3">
            <div className="flex-1">
              <p className="text-[15px] font-bold">
                {info.sectionName}구역 {info.rowNo}열 {info.colNo}번
              </p>
              <p className="text-[13px] text-sub tabular-nums">전석 {formatKrw(TICKET_PRICE)}</p>
            </div>
            <button
              onClick={held?.optimistic ? undefined : held ? goPay : holdSeat}
              disabled={!!held?.optimistic}
              className="rounded-cta bg-brand px-6 py-3.5 text-[15px] font-bold text-white transition-transform active:scale-[.96] disabled:opacity-60"
              style={{ boxShadow: "var(--shadow-cta)" }}
            >
              {held?.optimistic ? "선점 요청 중…" : held ? "결제하기" : "좌석 선점"}
            </button>
          </div>
        </div>
      )}
    </main>
  );
}
