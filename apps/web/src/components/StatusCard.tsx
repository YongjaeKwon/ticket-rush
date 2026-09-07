import Link from "next/link";

/** 진행할 수 없는 상태를 알리는 카드 — 제목, 한 줄 설명, 다음 행동(링크 또는 버튼). */
export function StatusCard({
  title,
  sub,
  cta,
  href,
  onClick,
  danger = true,
}: {
  title: string;
  sub: string;
  cta: string;
  href?: string;
  onClick?: () => void;
  danger?: boolean;
}) {
  const ctaClass = "mt-4 inline-block rounded-cta bg-brand px-6 py-3 text-sm font-bold text-white";
  return (
    <section
      className="mt-4 rounded-card bg-surface p-5 text-center"
      style={{ boxShadow: "var(--shadow-card)" }}
    >
      <p className={`text-sm font-bold ${danger ? "text-danger" : ""}`}>{title}</p>
      <p className="mt-1 text-xs text-sub">{sub}</p>
      {href ? (
        <Link href={href} className={ctaClass}>
          {cta}
        </Link>
      ) : (
        <button type="button" onClick={onClick} className={ctaClass}>
          {cta}
        </button>
      )}
    </section>
  );
}
