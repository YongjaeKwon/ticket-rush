package com.ticketing.catalog.adapter.in.web;

import com.ticketing.catalog.domain.EventDetail;
import com.ticketing.catalog.domain.EventSummary;
import com.ticketing.catalog.domain.SeatLayout;
import com.ticketing.catalog.domain.SeatStatusBitmap;

import java.time.LocalDateTime;
import java.util.List;

/** 응답 DTO 모음. byte[]는 Jackson이 Base64 문자열로 직렬화한다 (8-3 계약). */
final class CatalogResponses {

    private CatalogResponses() {
    }

    record EventSummaryResponse(long id, String title, String venue, LocalDateTime openAt) {

        static EventSummaryResponse from(EventSummary e) {
            return new EventSummaryResponse(e.id(), e.title(), e.venue(), e.openAt());
        }
    }

    record EventDetailResponse(long id, String title, String venue, LocalDateTime openAt,
                               List<ScheduleResponse> schedules) {

        record ScheduleResponse(long id, LocalDateTime startsAt) {
        }

        static EventDetailResponse from(EventDetail e) {
            return new EventDetailResponse(e.id(), e.title(), e.venue(), e.openAt(),
                    e.schedules().stream()
                            .map(s -> new ScheduleResponse(s.id(), s.startsAt()))
                            .toList());
        }
    }

    record SeatLayoutResponse(long scheduleId, List<SectionResponse> sections) {

        record SectionResponse(long id, String name, int seatCount, List<SeatResponse> seats) {
        }

        record SeatResponse(long id, int rowNo, int colNo) {
        }

        static SeatLayoutResponse from(SeatLayout layout) {
            return new SeatLayoutResponse(layout.scheduleId(), layout.sections().stream()
                    .map(sec -> new SectionResponse(sec.id(), sec.name(), sec.seats().size(),
                            sec.seats().stream()
                                    .map(s -> new SeatResponse(s.id(), s.rowNo(), s.colNo()))
                                    .toList()))
                    .toList());
        }
    }

    record SeatStatusResponse(long scheduleId, List<SectionStatusResponse> sections) {

        record SectionStatusResponse(long sectionId, int seatCount, byte[] bitmap) {
        }

        static SeatStatusResponse from(SeatStatusBitmap status) {
            return new SeatStatusResponse(status.scheduleId(), status.sections().stream()
                    .map(s -> new SectionStatusResponse(s.sectionId(), s.seatCount(), s.bitmap()))
                    .toList());
        }
    }
}
