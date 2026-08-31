"use client";

import { useEffect, useState } from "react";
import { getAdmissionToken } from "@/lib/user";

export function SeatsPlaceholder({ scheduleId }: { scheduleId: number }) {
  const [hasToken, setHasToken] = useState<boolean | null>(null);

  useEffect(() => {
    setHasToken(getAdmissionToken(scheduleId) !== null);
  }, [scheduleId]);

  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col px-4 pt-6">
      <span className="text-[17px] font-bold">좌석 선택</span>
      <section
        className="mt-4 rounded-card bg-surface p-5 text-center"
        style={{ boxShadow: "var(--shadow-card)" }}
      >
        {hasToken === null ? null : hasToken ? (
          <>
            <p className="text-sm font-bold text-brand">입장권 확인 완료</p>
            <p className="mt-1 text-xs text-sub">
              대기열을 통과했습니다. Canvas 좌석맵은 다음 단위에서 이 자리에 붙습니다.
            </p>
          </>
        ) : (
          <>
            <p className="text-sm font-bold text-danger">입장권이 없습니다</p>
            <p className="mt-1 text-xs text-sub">대기열을 먼저 통과해야 좌석을 고를 수 있어요.</p>
          </>
        )}
      </section>
    </main>
  );
}
