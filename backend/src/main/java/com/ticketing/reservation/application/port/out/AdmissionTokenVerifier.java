package com.ticketing.reservation.application.port.out;

import java.util.Optional;

/**
 * 입장권 검증. queue가 발급한 JWT의 "서명만" 확인한다 — queue를 호출하지 않으므로
 * 3단계에서 queue가 별도 서비스로 떨어져 나가도 이 코드는 그대로다.
 */
public interface AdmissionTokenVerifier {

    /** 서명·만료가 유효하면 claims를 돌려준다. 아니면 빈 값. */
    Optional<AdmissionClaims> verify(String token);

    record AdmissionClaims(long scheduleId, String userId) {
    }
}
