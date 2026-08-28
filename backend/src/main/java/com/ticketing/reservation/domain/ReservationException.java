package com.ticketing.reservation.domain;

/**
 * 도메인 규칙 위반. 순수 자바 — 스프링을 모르므로 HTTP 변환은 어댑터의 일이다.
 * code는 SEAT_ALREADY_HELD 형식.
 */
public class ReservationException extends RuntimeException {

    private final String code;

    public ReservationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
