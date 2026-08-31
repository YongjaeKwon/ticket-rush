package com.ticketing.queue.adapter.out.token;

import com.ticketing.queue.application.port.out.AdmissionTokenIssuer;
import com.ticketing.shared.token.HmacJwt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/** 입장권 = {sub: userId, scheduleId, exp} 를 HS256으로 서명한 JWT. */
@Component
class HmacAdmissionTokenIssuerAdapter implements AdmissionTokenIssuer {

    private final String secret;
    private final Duration ttl;
    private final Clock clock;

    HmacAdmissionTokenIssuerAdapter(@Value("${admission.jwt.secret}") String secret,
                                    @Value("${admission.jwt.ttl:10m}") Duration ttl,
                                    Clock clock) {
        this.secret = secret;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public String issue(long scheduleId, String userId) {
        return HmacJwt.sign(Map.of(
                "sub", userId,
                "scheduleId", scheduleId,
                "exp", clock.instant().plus(ttl).getEpochSecond()), secret);
    }
}
