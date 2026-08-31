import { QueueClient } from "./queue-client";

// 대기열은 순번이 계속 바뀌는 화면이라 전부 클라이언트에서 돈다 (ARCHITECTURE 6절).
export default async function QueuePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <QueueClient scheduleId={Number(id)} />;
}
