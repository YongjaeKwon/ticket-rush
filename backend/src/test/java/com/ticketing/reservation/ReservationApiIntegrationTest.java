package com.ticketing.reservation;

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
import org.springframework.http.HttpMethod;
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

/** REST API 전 구간 — 홀드/확정/취소/조회와 Idempotency-Key 동작을 HTTP 레벨에서 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "hold-expiry.enabled=false")
@AutoConfigureTestRestTemplate
@Testcontainers
class ReservationApiIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    StringRedisTemplate redisTemplate;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM confirmed_seat").update();
        jdbc.sql("DELETE FROM reservation").update();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private HttpHeaders headersFor(String userId, String idemKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", userId);
        if (idemKey != null) {
            headers.set("Idempotency-Key", idemKey);
        }
        return headers;
    }

    private ResponseEntity<JsonNode> hold(String userId, long seatId, String idemKey) {
        return rest.postForEntity("/api/reservations",
                new HttpEntity<>("{\"scheduleId\":1,\"seatId\":" + seatId + "}", headersFor(userId, idemKey)),
                JsonNode.class);
    }

    @Test
    void 홀드_API는_201과_Location과_만료시각을_돌려준다() {
        ResponseEntity<JsonNode> response = hold("user-1", 60L, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = response.getBody().get("reservationId").asLong();
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("/api/reservations/" + id);
        assertThat(response.getBody().get("expiresAt").asText()).isNotBlank();
    }

    @Test
    void 같은_키로_재시도하면_요청을_다시_처리하지_않고_저장된_응답을_재생한다() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = hold("user-1", 61L, key);
        ResponseEntity<JsonNode> second = hold("user-1", 61L, key);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("reservationId").asLong())
                .isEqualTo(first.getBody().get("reservationId").asLong());
        // 예매는 한 건만 생겼다
        Long count = jdbc.sql("SELECT COUNT(*) FROM reservation WHERE seat_id = 61")
                .query(Long.class).single();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void 같은_키에_다른_본문이면_409_IDEMPOTENCY_CONFLICT다() {
        String key = UUID.randomUUID().toString();
        hold("user-1", 62L, key);

        ResponseEntity<JsonNode> conflict = hold("user-1", 63L, key);   // 다른 좌석 = 다른 본문

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void 홀드에_Idempotency_Key가_없으면_400이다() {
        ResponseEntity<JsonNode> response = hold("user-1", 64L, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
    }

    @Test
    void 이미_선점된_좌석은_409_SEAT_ALREADY_HELD_problem_json이다() {
        hold("user-1", 65L, UUID.randomUUID().toString());

        ResponseEntity<JsonNode> response = hold("user-2", 65L, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(response.getBody().get("code").asText()).isEqualTo("SEAT_ALREADY_HELD");
    }

    @Test
    void 확정_API는_결제까지_끝내고_CONFIRMED를_돌려준다() {
        long id = hold("user-1", 66L, UUID.randomUUID().toString())
                .getBody().get("reservationId").asLong();

        ResponseEntity<JsonNode> response = rest.postForEntity("/api/reservations/" + id + "/confirm",
                new HttpEntity<>("", headersFor("user-1", UUID.randomUUID().toString())), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(response.getBody().get("paymentTransactionId").asText()).startsWith("mock-");
    }

    @Test
    void 확정도_같은_키_재시도는_저장된_응답을_재생하고_결제는_한_번만_된다() {
        long id = hold("user-1", 67L, UUID.randomUUID().toString())
                .getBody().get("reservationId").asLong();
        String key = UUID.randomUUID().toString();
        var entity = new HttpEntity<>("", headersFor("user-1", key));

        ResponseEntity<JsonNode> first = rest.postForEntity(
                "/api/reservations/" + id + "/confirm", entity, JsonNode.class);
        ResponseEntity<JsonNode> second = rest.postForEntity(
                "/api/reservations/" + id + "/confirm", entity, JsonNode.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");
        assertThat(second.getBody().get("paymentTransactionId").asText())
                .isEqualTo(first.getBody().get("paymentTransactionId").asText());
        Long confirmed = jdbc.sql("SELECT COUNT(*) FROM confirmed_seat WHERE seat_id = 67")
                .query(Long.class).single();
        assertThat(confirmed).isEqualTo(1L);
    }

    @Test
    void 취소_API는_204를_돌려주고_상태를_CANCELLED로_바꾼다() {
        long id = hold("user-1", 68L, UUID.randomUUID().toString())
                .getBody().get("reservationId").asLong();

        ResponseEntity<Void> response = rest.exchange("/api/reservations/" + id,
                HttpMethod.DELETE, new HttpEntity<>(headersFor("user-1", null)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String status = jdbc.sql("SELECT status FROM reservation WHERE id = :id")
                .param("id", id).query(String.class).single();
        assertThat(status).isEqualTo("CANCELLED");
    }

    @Test
    void 조회_API는_본인_예매만_보여준다() {
        long id = hold("user-1", 69L, UUID.randomUUID().toString())
                .getBody().get("reservationId").asLong();

        ResponseEntity<JsonNode> mine = rest.exchange("/api/reservations/" + id,
                HttpMethod.GET, new HttpEntity<>(headersFor("user-1", null)), JsonNode.class);
        ResponseEntity<JsonNode> others = rest.exchange("/api/reservations/" + id,
                HttpMethod.GET, new HttpEntity<>(headersFor("user-2", null)), JsonNode.class);

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody().get("status").asText()).isEqualTo("HELD");
        assertThat(mine.getBody().get("seatId").asLong()).isEqualTo(69L);
        assertThat(others.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(others.getBody().get("code").asText()).isEqualTo("RESERVATION_NOT_OWNED");
    }
}
