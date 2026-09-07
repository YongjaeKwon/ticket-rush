import { ApiError } from "@ticket-rush/api-client";
import { StatusCard } from "./StatusCard";

/** 예매 조회 실패를 원인별로 안내한다 — 서버에 못 닿음(다시 읽기) / 남의 예매 / 없는 예매. 결제·완료 화면이 같이 쓴다. */
export function ReservationLoadError({
  error,
  onRetry,
  verb,
}: {
  error: unknown;
  onRetry: () => void;
  /** "결제", "확인" — 제목의 동사 */
  verb: string;
}) {
  if (!(error instanceof ApiError)) {
    // 서버까지 못 갔다 — 만료 시각을 모르니 카운트다운도 지어내지 않고 다시 읽기만 권한다
    return (
      <StatusCard
        title="예매 정보를 불러오지 못했어요"
        sub="연결을 확인하고 다시 시도해 주세요."
        cta="다시 불러오기"
        onClick={onRetry}
      />
    );
  }
  if (error.code === "RESERVATION_NOT_OWNED") {
    return (
      <StatusCard
        title={`본인의 예매만 ${verb}할 수 있어요`}
        sub="예매한 브라우저에서 열어 주세요."
        href="/"
        cta="공연 목록으로"
      />
    );
  }
  return (
    <StatusCard
      title="예매를 찾을 수 없어요"
      sub="링크가 잘못된 것 같아요. 공연 목록에서 다시 시작해 주세요."
      href="/"
      cta="공연 목록으로"
    />
  );
}
