package com.ticketing.catalog.application.port.in;

import com.ticketing.catalog.domain.SeatLayout;

public interface GetSeatLayoutUseCase {

    /** 없는 회차면 SCHEDULE_NOT_FOUND. */
    SeatLayout getLayout(long scheduleId);
}
