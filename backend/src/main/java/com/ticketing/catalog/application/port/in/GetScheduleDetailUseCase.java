package com.ticketing.catalog.application.port.in;

import com.ticketing.catalog.domain.ScheduleDetail;

public interface GetScheduleDetailUseCase {

    /** 없는 회차면 SCHEDULE_NOT_FOUND. */
    ScheduleDetail getSchedule(long scheduleId);
}
