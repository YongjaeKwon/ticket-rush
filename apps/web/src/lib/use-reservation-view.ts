import { useCallback, useEffect, useState } from "react";
import {
  describeSeat,
  getReservation,
  getSchedule,
  getSeatLayout,
  type Reservation,
  type ScheduleDetail,
} from "./reservation";

export type ReservationView = {
  reservation: Reservation | null;
  schedule: ScheduleDetail | null;
  seatLabel: string | null;
  error: unknown;
  loading: boolean;
  /** 예매를 서버에서 다시 읽는다. 실패하면 null */
  reload: () => Promise<Reservation | null>;
};

/**
 * 예매 → (회차·공연, 좌석 이름)을 한 번에 읽는다. 결제·완료 화면이 같이 쓴다.
 * URL의 예매 id 하나가 유일한 입력이라 새로고침·뒤로가기·탭 복제가 모두 같은 경로를 탄다.
 */
export function useReservationView(reservationId: number): ReservationView {
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [schedule, setSchedule] = useState<ScheduleDetail | null>(null);
  const [seatLabel, setSeatLabel] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    try {
      const latest = await getReservation(reservationId);
      setReservation(latest);
      setError(null);
      return latest;
    } catch (e) {
      setError(e);
      return null;
    } finally {
      setLoading(false);
    }
  }, [reservationId]);

  useEffect(() => {
    void reload();
  }, [reload]);

  // 회차·배치는 예매를 읽은 뒤에 가져온다. 둘 다 정적이라 한 번만 읽는다 (배치는 불변 캐시)
  const scheduleId = reservation?.scheduleId;
  const seatId = reservation?.seatId;
  useEffect(() => {
    if (scheduleId === undefined || seatId === undefined) return;
    let cancelled = false;
    getSchedule(scheduleId)
      .then((s) => !cancelled && setSchedule(s))
      .catch(() => {});
    getSeatLayout(scheduleId)
      .then((layout) => !cancelled && setSeatLabel(describeSeat(layout, seatId)))
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [scheduleId, seatId]);

  return { reservation, schedule, seatLabel, error, loading, reload };
}
