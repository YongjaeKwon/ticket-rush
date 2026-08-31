// GATE 아트 서피스 — 딥 인디고 + 좌석 도트 패턴. 좌석맵이 곧 브랜드 그래픽이다.
export function ArtPanel({
  className = "",
  children,
}: {
  className?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className={`relative overflow-hidden text-white ${className}`}
      style={{
        backgroundColor: "var(--art)",
        backgroundImage:
          "radial-gradient(rgba(255,255,255,.12) 1.3px, transparent 1.7px)",
        backgroundSize: "16px 16px",
        backgroundPosition: "-5px -7px",
      }}
    >
      {children}
    </div>
  );
}

/** 도트 몇 개가 켜진 좌석 시그니처 */
export function SeatSig({ pattern = [0, 1, 0, 0, 1, 1, 0] }: { pattern?: number[] }) {
  return (
    <div className="flex gap-[5px]">
      {pattern.map((on, i) => (
        <i
          key={i}
          className="h-2 w-2 rounded-[2.5px]"
          style={{ background: on ? "#fff" : "rgba(255,255,255,.22)" }}
        />
      ))}
    </div>
  );
}
