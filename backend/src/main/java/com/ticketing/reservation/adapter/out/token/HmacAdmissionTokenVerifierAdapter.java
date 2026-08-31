package com.ticketing.reservation.adapter.out.token;

import com.ticketing.reservation.application.port.out.AdmissionTokenVerifier;
import com.ticketing.shared.token.HmacJwt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class HmacAdmissionTokenVerifierAdapter implements AdmissionTokenVerifier {

    private final String secret;

    HmacAdmissionTokenVerifierAdapter(@Value("${admission.jwt.secret}") String secret) {
        this.secret = secret;
    }

    @Override
    public Optional<AdmissionClaims> verify(String token) {
        return HmacJwt.verify(token, secret)
                .map(claims -> new AdmissionClaims(
                        claims.get("scheduleId").asLong(),
                        claims.get("sub").asString()));
    }
}
