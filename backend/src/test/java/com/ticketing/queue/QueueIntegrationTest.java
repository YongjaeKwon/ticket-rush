package com.ticketing.queue;

import com.ticketing.queue.application.port.in.AdmitWaitingUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대기열 전 구간 검증 — 줄서기, 순번 폴링, N명 입장, 그리고 발급된 입장권으로
 * 실제 좌석 홀드까지. queue와 reservation이 코드가 아니라 JWT 서명으로만 이어지는
 * 계약을 여기서 확인한다. 스케줄러는 끄고 입장은 유스케이스를 직접 부른다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"queue.admission.enabled=false", "queue.admission.batch-size=2",
                "hold-expiry.enabled=false"})
@AutoConfigureTestRestTemplate
@Testcontainers
class QueueIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AdmitWaitingUseCase admitWaiting;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM reservation").update();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private HttpHeaders userHeaders(String userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId);
        return headers;
    }

    private JsonNode enter(String userId) {
        return rest.postForEntity("/api/schedules/1/queue",
                new HttpEntity<>("", userHeaders(userId)), JsonNode.class).getBody();
    }

    private JsonNode me(String userId) {
        return rest.exchange("/api/schedules/1/queue/me", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(userHeaders(userId)), JsonNode.class).getBody();
    }

    @Test
    void 줄을_선_순서대로_순번이_붙고_재진입해도_자리를_잃지_않는다() {
        assertThat(enter("user-1").get("position").asLong()).isEqualTo(1);
        assertThat(enter("user-2").get("position").asLong()).isEqualTo(2);
        assertThat(enter("user-3").get("position").asLong()).isEqualTo(3);

        // user-1이 새로고침(재진입)해도 1번 자리 그대로
        assertThat(enter("user-1").get("position").asLong()).isEqualTo(1);
    }

    @Test
    void 입장_배치가_앞에서_2명씩_입장시키고_입장권이_발급된다() {
        enter("user-1");
        enter("user-2");
        enter("user-3");

        int admitted = admitWaiting.admitNext();    // batch-size=2

        assertThat(admitted).isEqualTo(2);
        JsonNode first = me("user-1");
        assertThat(first.get("admitted").asBoolean()).isTrue();
        assertThat(first.get("token").asText()).contains(".");   // JWT 형태
        assertThat(me("user-2").get("admitted").asBoolean()).isTrue();

        // 3번째 사용자는 아직 대기 — 이제 맨 앞(1번)이 됐다
        JsonNode third = me("user-3");
        assertThat(third.get("admitted").asBoolean()).isFalse();
        assertThat(third.get("position").asLong()).isEqualTo(1);

        // 입장권은 10분 뒤 사라진다
        assertThat(redisTemplate.getExpire("admitted:1:user-1")).isBetween(500L, 600L);
    }

    @Test
    void 발급된_입장권으로_실제_좌석_홀드까지_이어진다() {
        enter("user-1");
        admitWaiting.admitNext();
        String token = me("user-1").get("token").asText();

        HttpHeaders headers = userHeaders("user-1");
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> hold = rest.postForEntity("/api/reservations",
                new HttpEntity<>("{\"scheduleId\":1,\"seatId\":77}", headers), JsonNode.class);

        assertThat(hold.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(hold.getBody().get("reservationId").asLong()).isPositive();
    }

    @Test
    void 다른_회차_입장권으로는_홀드할_수_없다() {
        enter("user-1");
        admitWaiting.admitNext();
        String tokenForSchedule1 = me("user-1").get("token").asText();

        HttpHeaders headers = userHeaders("user-1");
        headers.setBearerAuth(tokenForSchedule1);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        // 토큰은 회차 1용인데 회차 2 좌석을 요청
        ResponseEntity<JsonNode> hold = rest.postForEntity("/api/reservations",
                new HttpEntity<>("{\"scheduleId\":2,\"seatId\":77}", headers), JsonNode.class);

        assertThat(hold.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(hold.getBody().get("code").asText()).isEqualTo("ADMISSION_REQUIRED");
    }

    @Test
    void 이미_입장한_사용자는_다시_줄을_서도_입장_상태다() {
        enter("user-1");
        admitWaiting.admitNext();

        JsonNode reentered = enter("user-1");   // 입장 후 재진입 시도

        assertThat(reentered.get("position").asLong()).isZero();
        assertThat(me("user-1").get("admitted").asBoolean()).isTrue();
    }
}
