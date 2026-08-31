// 화면이 쓰는 API 호출 모음 — 경로·타입을 생성 스키마에 1:1로 묶는다.
import { api, type paths, type components } from "@ticket-rush/api-client";

export type EventSummary = components["schemas"]["EventSummaryResponse"];
export type EventDetail = components["schemas"]["EventDetailResponse"];

type EventsResponse =
  paths["/api/events"]["get"]["responses"]["200"]["content"]["*/*"];
type EventResponse =
  paths["/api/events/{eventId}"]["get"]["responses"]["200"]["content"]["*/*"];

export function getEvents(): Promise<EventsResponse> {
  // 목록은 자주 안 바뀐다 — 30초 캐시(ISR 성격)
  return api<EventsResponse>("/api/events", { next: { revalidate: 30 } });
}

export function getEvent(eventId: number): Promise<EventResponse> {
  return api<EventResponse>(`/api/events/${eventId}`, { next: { revalidate: 30 } });
}
