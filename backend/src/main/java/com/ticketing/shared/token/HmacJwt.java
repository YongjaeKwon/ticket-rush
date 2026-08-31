package com.ticketing.shared.token;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * 입장권용 최소 JWT (HS256). 라이브러리 대신 JDK 암호화만 쓴다 — 근거는 docs/adr/0005.
 * 구조: base64url(헤더).base64url(본문).base64url(서명). 서명 = HmacSHA256(헤더.본문, 비밀키).
 * queue가 발급하고 reservation은 서명만 검증한다 — 두 모듈은 비밀키만 공유할 뿐 서로를 모른다.
 */
public final class HmacJwt {

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private HmacJwt() {
    }

    /** claims에 exp(에포크 초)를 포함해 서명한 토큰을 만든다. */
    public static String sign(Map<String, Object> claims, String secret) {
        String head = ENC.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));
        String body = ENC.encodeToString(JSON.writeValueAsBytes(claims));
        return head + "." + body + "." + ENC.encodeToString(hmac(head + "." + body, secret));
    }

    /** 서명·만료가 유효하면 본문(claims)을 돌려준다. 형식이 이상하면 빈 값. */
    public static Optional<JsonNode> verify(String token, String secret) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            byte[] expected = hmac(parts[0] + "." + parts[1], secret);
            byte[] actual = DEC.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {   // 상수 시간 비교
                return Optional.empty();
            }
            JsonNode claims = JSON.readTree(DEC.decode(parts[1]));
            if (!claims.has("exp") || claims.get("exp").asLong() < Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
