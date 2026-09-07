// 가격 — 백엔드에 가격 모델이 없어(2단계 범위 밖) 화면 상수로 둔다. 목록·상세·좌석맵·결제·완료가 모두 이 값을 쓴다.
export const TICKET_PRICE = 132_000;
export const BOOKING_FEE = 2_000;
export const TOTAL_PRICE = TICKET_PRICE + BOOKING_FEE;

/** "134,000원" */
export function formatKrw(amount: number): string {
  return `${amount.toLocaleString("ko-KR")}원`;
}
