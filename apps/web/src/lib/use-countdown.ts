import { useEffect, useState } from "react";
import { parseUtc } from "./format";

/** 서버가 준 만료 시각까지 남은 초. 시각이 없으면 null. 0.5초마다 갱신한다. */
export function useCountdown(expiresAt: string | undefined): number | null {
  const [remain, setRemain] = useState<number | null>(null);

  useEffect(() => {
    if (!expiresAt) {
      setRemain(null);
      return;
    }
    const deadline = parseUtc(expiresAt).getTime();
    const tick = () => setRemain(Math.max(0, Math.ceil((deadline - Date.now()) / 1000)));
    tick();
    const timer = setInterval(tick, 500);
    // 백그라운드 탭에서는 타이머가 느려진다 — 돌아오는 순간 바로 맞춘다
    document.addEventListener("visibilitychange", tick);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", tick);
    };
  }, [expiresAt]);

  return remain;
}
