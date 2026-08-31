"use client";

// 잠금화면 위젯형 오픈 카운트다운 — GATE 프로토타입의 그 카드.
// 서버가 그리면 시간이 멈추므로 클라이언트 컴포넌트다.
import { useEffect, useState } from "react";
import { parseUtc } from "@/lib/format";

function remain(openAt: string): number {
  return Math.max(0, Math.floor((parseUtc(openAt).getTime() - Date.now()) / 1000));
}

export function OpenCountdown({ openAt }: { openAt: string }) {
  // SSR과 첫 클라이언트 렌더가 다르면 hydration 경고 — 마운트 후에만 숫자를 채운다
  const [seconds, setSeconds] = useState<number | null>(null);

  useEffect(() => {
    setSeconds(remain(openAt));
    const timer = setInterval(() => setSeconds(remain(openAt)), 250);
    return () => clearInterval(timer);
  }, [openAt]);

  const opened = seconds === 0;
  const urgent = seconds !== null && seconds > 0 && seconds < 11;
  const pad = (n: number) => String(n).padStart(2, "0");
  const display =
    seconds === null
      ? ["--", "--", "--"]
      : [pad(Math.floor(seconds / 3600)), pad(Math.floor((seconds % 3600) / 60)), pad(seconds % 60)];

  return (
    <div className="text-center">
      <p className="text-[13px] font-semibold text-white/55">
        {opened ? "예매가 시작됐습니다" : "예매 오픈까지"}
      </p>
      <div
        className="mt-2 flex items-baseline justify-center gap-0.5 tabular-nums"
        data-testid="countdown"
      >
        {display.map((value, i) => (
          <span key={i} className="flex items-baseline">
            {i > 0 && <span className="px-0.5 text-[34px] font-bold text-white/35">:</span>}
            <b
              className="min-w-[70px] text-[52px] font-extrabold leading-tight tracking-tight transition-colors"
              style={{ color: urgent ? "#FF9A8B" : "#fff" }}
            >
              {value}
            </b>
          </span>
        ))}
      </div>
      <p className="mt-2 text-xs text-white/45">
        {opened ? "대기열에 입장할 수 있습니다" : "오픈과 동시에 대기열이 시작됩니다"}
      </p>
    </div>
  );
}
