import { SeatMapClient } from "./seat-map-client";

// 좌석맵은 저사양 폰을 위해 클라이언트 전용 + Canvas다 (ARCHITECTURE 6절).
export default async function SeatsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <SeatMapClient scheduleId={Number(id)} />;
}
