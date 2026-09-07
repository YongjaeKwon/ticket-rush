"use client";

// 하단 토스트 — 에러 코드(기계용)와 문장(사람용)을 같이 보여준다. sticky가 아니면 4초 뒤 사라진다.
import { useEffect, useRef, useState } from "react";

export type ToastMessage = { code?: string; text: string; sticky?: boolean };

export function Toast({ message, onDone }: { message: ToastMessage | null; onDone: () => void }) {
  // 사라지는 애니메이션 동안 마지막 문구를 유지한다
  const [shown, setShown] = useState<ToastMessage | null>(message);
  const onDoneRef = useRef(onDone);

  useEffect(() => {
    onDoneRef.current = onDone;
  });

  useEffect(() => {
    if (message) setShown(message);
    if (!message || message.sticky) return;
    const timer = setTimeout(() => onDoneRef.current(), 4_000);
    return () => clearTimeout(timer);
  }, [message]);

  return (
    <div
      role="status"
      aria-live="polite"
      aria-hidden={!message}
      className={`pointer-events-none fixed inset-x-4 bottom-[148px] z-20 mx-auto max-w-md rounded-cell px-4 py-3 text-[13px] text-white transition-[transform,opacity] duration-300 ${
        message ? "translate-y-0 scale-100 opacity-100" : "translate-y-3 scale-[.97] opacity-0"
      }`}
      style={{
        background: "var(--capsule)",
        boxShadow: "var(--shadow-capsule)",
        transitionTimingFunction: "var(--spring)",
      }}
    >
      {shown?.code && (
        <span className="block font-mono text-[10px] tracking-wide" style={{ color: "var(--code-on-dark)" }}>
          {shown.code}
        </span>
      )}
      {shown?.text}
    </div>
  );
}
