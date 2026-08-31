package com.ticketing.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Idempotency-Key 처리 (ARCHITECTURE 8-2).
 * 모바일에서는 같은 요청이 두 번 간다 — 상태를 바꾸는 POST는 키를 필수로 받고,
 * 첫 응답을 Redis(idem:{userId}:{key}, TTL 24h)에 저장해 같은 키면 그대로 재생한다.
 * 같은 키에 다른 본문이 오면 409 IDEMPOTENCY_CONFLICT.
 *
 * 같은 키의 "동시" 요청 레이스는 여기서 막지 않는다 — 그 경우에도 좌석 정합성은
 * Redis SET NX와 confirmed_seat PK가 지키고, 레이스 방어는 4단계 부하 검증에서 재논의.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;
    private final JsonMapper json = JsonMapper.builder().build();

    public IdempotencyFilter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws IOException, jakarta.servlet.ServletException {
        String key = request.getHeader("Idempotency-Key");
        String userId = request.getHeader("X-User-Id");
        if (key == null || key.isBlank()) {
            writeProblem(response, 400, "IDEMPOTENCY_KEY_REQUIRED",
                    "상태를 바꾸는 요청에는 Idempotency-Key 헤더가 필요합니다");
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String fingerprint = sha256(request.getMethod() + "|" + request.getRequestURI() + "|"
                + new String(body, StandardCharsets.UTF_8));
        String redisKey = "idem:" + userId + ":" + key;

        String stored = redis.opsForValue().get(redisKey);
        if (stored != null) {
            JsonNode saved = json.readTree(stored);
            if (!saved.get("fingerprint").asText().equals(fingerprint)) {
                writeProblem(response, 409, "IDEMPOTENCY_CONFLICT",
                        "같은 Idempotency-Key로 다른 요청이 이미 처리됐습니다");
                return;
            }
            response.setStatus(saved.get("status").asInt());
            response.setContentType(saved.get("contentType").asText());
            if (saved.hasNonNull("location")) {
                response.setHeader("Location", saved.get("location").asText());
            }
            response.setHeader("Idempotency-Replayed", "true");
            response.getWriter().write(saved.get("body").asText());
            return;
        }

        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        chain.doFilter(withCachedBody(request, body), wrappedResponse);

        // 5xx는 저장하지 않는다 — 서버 오류는 재시도가 새로 처리돼야 한다
        if (wrappedResponse.getStatus() < 500) {
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("fingerprint", fingerprint);
            record.put("status", wrappedResponse.getStatus());
            record.put("contentType", String.valueOf(wrappedResponse.getContentType()));
            record.put("location", wrappedResponse.getHeader("Location"));
            record.put("body", new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8));
            redis.opsForValue().set(redisKey, json.writeValueAsString(record), TTL);
        }
        wrappedResponse.copyBodyToResponse();
    }

    /* 본문을 지문 계산에 먼저 읽었으니, 컨트롤러가 다시 읽을 수 있게 감싼다 */
    private HttpServletRequest withCachedBody(HttpServletRequest request, byte[] body) {
        return new HttpServletRequestWrapper(request) {
            @Override
            public ServletInputStream getInputStream() {
                ByteArrayInputStream in = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override
                    public int read() {
                        return in.read();
                    }

                    @Override
                    public boolean isFinished() {
                        return in.available() == 0;
                    }

                    @Override
                    public boolean isReady() {
                        return true;
                    }

                    @Override
                    public void setReadListener(ReadListener listener) {
                    }
                };
            }
        };
    }

    private void writeProblem(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of(
                "title", code, "status", status, "detail", detail, "code", code)));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
