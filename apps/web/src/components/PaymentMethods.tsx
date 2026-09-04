"use client";

// 결제수단 타일 — 프로토타입(gate1)의 pm-* 모노그램. 결제 자체는 mock PG라 선택은 안내 문구만 바꾼다.
// 진짜 라디오 입력을 숨겨 두어 키보드(화살표 이동)·스크린리더 동작은 브라우저가 맡는다.
import type { ReactNode } from "react";

export type PaymentMethodId = "card" | "kakao" | "naver" | "toss" | "payco" | "phone";

type Method = {
  id: PaymentMethodId;
  label: string;
  note: string;
  mark: ReactNode;
  bg: string;
  fg: string;
};

const CardMark = () => (
  <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" strokeWidth="2">
    <rect x="2.5" y="5" width="19" height="14" rx="2.5" />
    <path d="M2.5 9.5h19" strokeWidth="2.6" />
  </svg>
);

const PhoneMark = () => (
  <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" strokeWidth="2">
    <rect x="7" y="2.5" width="10" height="19" rx="2.5" />
    <path d="M11 18.5h2" strokeLinecap="round" />
  </svg>
);

// 모노그램 색은 각 사업자의 브랜드 색 — 디자인 토큰이 아니라 외부 아이덴티티라 여기서만 쓴다
export const PAYMENT_METHODS: Method[] = [
  { id: "card", label: "카드", note: "결제창에서 카드사를 선택합니다 · 앱카드 지원",
    mark: <CardMark />, bg: "rgba(46,91,255,.1)", fg: "var(--brand)" },
  { id: "kakao", label: "카카오페이", note: "카카오톡으로 결제 요청을 보냅니다",
    mark: "K", bg: "#FEE500", fg: "#191600" },
  { id: "naver", label: "네이버페이", note: "네이버페이 포인트를 함께 쓸 수 있습니다",
    mark: "N", bg: "#03C75A", fg: "#fff" },
  { id: "toss", label: "토스페이", note: "토스 앱에서 바로 결제합니다",
    mark: "T", bg: "#0064FF", fg: "#fff" },
  { id: "payco", label: "페이코", note: "페이코 포인트 적립·사용이 가능합니다",
    mark: "P", bg: "#FA2846", fg: "#fff" },
  { id: "phone", label: "휴대폰", note: "다음 달 통신사 요금과 함께 청구됩니다",
    mark: <PhoneMark />, bg: "rgba(60,60,67,.09)", fg: "var(--ink)" },
];

export function PaymentMethods({
  value,
  onChange,
  disabled = false,
}: {
  value: PaymentMethodId;
  onChange: (id: PaymentMethodId) => void;
  disabled?: boolean;
}) {
  return (
    <fieldset className="grid grid-cols-3 gap-2" disabled={disabled}>
      <legend className="sr-only">결제 수단</legend>
      {PAYMENT_METHODS.map((m) => {
        const on = m.id === value;
        return (
          <label
            key={m.id}
            className={`relative flex cursor-pointer flex-col items-center gap-2 rounded-cell border-[1.5px] px-1.5 pb-2.5 pt-3 text-[12.5px] transition-[border-color,background-color,transform] has-focus-visible:ring-2 has-focus-visible:ring-brand/40 active:scale-[.96] ${
              on ? "border-brand bg-brand/5 font-bold text-brand" : "bg-surface font-semibold text-sub"
            } ${disabled ? "opacity-60" : ""}`}
            style={{ borderColor: on ? undefined : "var(--line)" }}
          >
            <input
              type="radio"
              name="payment-method"
              value={m.id}
              className="sr-only"
              checked={on}
              onChange={() => onChange(m.id)}
            />
            <span
              className="flex h-9 w-9 items-center justify-center rounded-[11px] text-[13px] font-extrabold"
              style={{ background: m.bg, color: m.fg }}
            >
              {m.mark}
            </span>
            {m.label}
            <span
              aria-hidden
              className={`absolute right-[7px] top-[7px] flex h-[17px] w-[17px] items-center justify-center rounded-full bg-brand transition-[opacity,transform] duration-200 ${
                on ? "scale-100 opacity-100" : "scale-[.4] opacity-0"
              }`}
              style={{ transitionTimingFunction: "var(--spring)" }}
            >
              <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round">
                <path d="M4.5 12.5l5 5 10-11" />
              </svg>
            </span>
          </label>
        );
      })}
    </fieldset>
  );
}
