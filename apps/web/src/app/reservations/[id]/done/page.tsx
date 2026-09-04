import { DoneClient } from "./done-client";

// 완료 화면도 예매를 서버에서 다시 읽는다 — 링크로 다시 열어도 같은 티켓이 보인다.
export default async function DonePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <DoneClient reservationId={Number(id)} />;
}
