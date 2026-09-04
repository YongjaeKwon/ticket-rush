import { PayClient } from "./pay-client";

// 결제는 클라이언트 전용 — 예매 상태는 사용자별·실시간이라 서버가 미리 그릴 것이 없다.
export default async function PayPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <PayClient reservationId={Number(id)} />;
}
