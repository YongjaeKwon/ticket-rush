package com.ticketing.catalog.adapter.out.persistence;

import com.ticketing.catalog.application.port.out.ConfirmedSeatReader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * confirmed_seat는 reservation의 테이블이지만 1단계에서는 읽기 전용
 * 네이티브 쿼리로 직접 본다 (docs/adr/0004). JPA 엔티티를 만들지 않는 이유:
 * 이 테이블의 매핑 주인은 나중에 생길 reservation 모듈이다.
 */
@Component
class JdbcConfirmedSeatReaderAdapter implements ConfirmedSeatReader {

    private final JdbcClient jdbc;

    JdbcConfirmedSeatReaderAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<Long> confirmedSeatIds(long scheduleId) {
        return new HashSet<>(jdbc.sql("SELECT seat_id FROM confirmed_seat WHERE schedule_id = :scheduleId")
                .param("scheduleId", scheduleId)
                .query(Long.class)
                .list());
    }
}
