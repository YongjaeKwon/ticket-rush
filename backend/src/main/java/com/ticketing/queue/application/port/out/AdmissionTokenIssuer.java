package com.ticketing.queue.application.port.out;

/** 입장권 발급. 구현은 HS256 JWT — reservation은 이 토큰의 서명만 검증한다. */
public interface AdmissionTokenIssuer {

    String issue(long scheduleId, String userId);
}
