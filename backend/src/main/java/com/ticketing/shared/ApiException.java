package com.ticketing.shared;

import org.springframework.http.HttpStatus;

/**
 * 비즈니스 에러 공통 예외. code는 SEAT_ALREADY_HELD 형식의 대문자 스네이크.
 * 응답 변환은 shared.web.GlobalExceptionHandler가 담당한다 (RFC 9457).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
