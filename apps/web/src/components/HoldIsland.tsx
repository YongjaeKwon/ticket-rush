// 선점 카운트다운 캡슐 — 좌석맵과 결제 화면이 같은 것을 쓴다.
// pending이면 아직 서버 응답 전이라 시간을 지어내지 않고 "요청 중"만 보여준다.
export function HoldIsland({
  remainSeconds,
  pending = false,
}: {
  remainSeconds: number;
  pending?: boolean;
}) {
  const minutes = Math.floor(remainSeconds / 60);
  const seconds = String(remainSeconds % 60).padStart(2, "0");
  return (
    <span
      className="flex items-center gap-2 rounded-full px-4 py-1.5 text-xs font-semibold text-white"
      style={{ background: "var(--capsule)", boxShadow: "var(--shadow-capsule)" }}
    >
      <i className="h-1.5 w-1.5 animate-pulse rounded-full bg-danger" />
      {pending ? (
        "선점 요청 중…"
      ) : (
        <>
          선점 중
          <b className="tabular-nums" style={{ color: "var(--code-on-dark)" }}>
            {minutes}:{seconds}
          </b>
        </>
      )}
    </span>
  );
}
