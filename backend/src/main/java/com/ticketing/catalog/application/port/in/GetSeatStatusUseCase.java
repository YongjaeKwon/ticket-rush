package com.ticketing.catalog.application.port.in;

import com.ticketing.catalog.domain.SeatStatusBitmap;

public interface GetSeatStatusUseCase {

    /** 없는 회차면 SCHEDULE_NOT_FOUND. */
    SeatStatusBitmap getStatus(long scheduleId);
}
