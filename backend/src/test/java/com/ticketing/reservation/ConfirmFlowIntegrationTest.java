package com.ticketing.reservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 비동기 확정의 전 구간을 HTTP부터 실물로 검증한다 — 웹이 겪는 그대로:
 * 홀드 → 확정 202 → 서랍 → 릴레이 → reservation.events → payment(mock PG 승인) → payment.events
 * → 결과 컨슈머 → 조회에서 CONFIRMED와 승인번호가 보인다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hold-expiry.enabled=false",
        "outbox.relay.poll-interval-ms=200",
        "payment.mock.failure-rate=0.0"})   // 승인 왕복을 검증한다 — mock PG 기본값에 기대지 않는다
@AutoConfigureTestRestTemplate
@Testcontainers
class ConfirmFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static final KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redisTemplate;

    @Autowired
    com.ticketing.queue.application.port.out.AdmissionTokenIssuer tokenIssuer;

    private HttpHeaders headersFor(String userId, String idemKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId);
        headers.setBearerAuth(tokenIssuer.issue(1L, userId));
        if (idemKey != null) {
            headers.set("Idempotency-Key", idemKey);
        }
        return headers;
    }

    @Test
    void 확정_202_뒤_결과_쪽지가_돌아오면_조회에서_CONFIRMED와_승인번호가_보인다() {
        long id = rest.postForEntity("/api/reservations",
                        new HttpEntity<>("{\"scheduleId\":1,\"seatId\":80}", headersFor("user-flow", UUID.randomUUID().toString())),
                        JsonNode.class)
                .getBody().get("reservationId").asLong();

        ResponseEntity<JsonNode> accepted = rest.postForEntity("/api/reservations/" + id + "/confirm",
                new HttpEntity<>("", headersFor("user-flow", UUID.randomUUID().toString())), JsonNode.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 웹 결제 화면이 하는 그대로 — 조회를 폴링해 판정을 기다린다
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            JsonNode view = rest.exchange("/api/reservations/" + id, HttpMethod.GET,
                    new HttpEntity<>(headersFor("user-flow", null)), JsonNode.class).getBody();
            assertThat(view.get("status").asText()).isEqualTo("CONFIRMED");
            assertThat(view.get("paymentStatus").asText()).isEqualTo("APPROVED");
            assertThat(view.get("paymentTransactionId").asText()).startsWith("mock-");
        });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 80").query(Long.class).single())
                .isEqualTo(1L);
        // 금액은 서버 설정값 그대로 결제됐다
        assertThat(jdbc.sql("SELECT amount FROM payment WHERE reservation_id = " + id).query(Integer.class).single())
                .isEqualTo(134_000);
        // 홀드 해제는 확정 커밋 뒤 — 같은 await 밖이라 잠시 기다린다
        await().atMost(Duration.ofSeconds(5)).until(() -> !Boolean.TRUE.equals(redisTemplate.hasKey("hold:1:80")));
    }
}
