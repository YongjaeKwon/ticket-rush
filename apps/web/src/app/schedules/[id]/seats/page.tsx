import { SeatsPlaceholder } from "./seats-placeholder";

// 좌석 선택 화면의 자리 — Canvas 좌석맵은 다음 단위(체크리스트 6번)에서 만든다.
// 지금은 대기열에서 넘어온 입장권이 살아 있는지만 보여준다.
export default async function SeatsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <SeatsPlaceholder scheduleId={Number(id)} />;
}
