package com.ticketing.reservation.application.port.out;

import com.ticketing.reservation.domain.ReservationHeld;

/**
 * 도메인 이벤트 발행. 1단계 구현은 Outbox 테이블 기록 —
 * "DB에 먼저 적고 나중에 보낸다". 실제 전송(Kafka)은 3단계.
 */
public interface EventPublisher {

    void publish(ReservationHeld event);
}
