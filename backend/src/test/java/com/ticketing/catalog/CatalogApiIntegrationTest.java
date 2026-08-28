package com.ticketing.catalog;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** 실물 MySQL(Testcontainers)에 Flyway V1~V3 적용 후 시드 기준으로 API를 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class CatalogApiIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    /* Redis는 이 테스트에서 안 쓰므로 헬스체크가 붙잡지 않게 비활성 */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("management.health.redis.enabled", () -> "false");
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    void 공연_목록에_시드_공연이_나온다() {
        JsonNode body = rest.getForObject("/api/events", JsonNode.class);

        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("title").asText()).isEqualTo("2026 TICKET RUSH LIVE");
        assertThat(body.get(0).get("id").asLong()).isEqualTo(1L);
    }

    @Test
    void 공연_상세에_회차가_붙는다() {
        JsonNode body = rest.getForObject("/api/events/1", JsonNode.class);

        assertThat(body.get("venue").asText()).isNotBlank();
        assertThat(body.get("schedules")).hasSize(1);
        assertThat(body.get("schedules").get(0).get("id").asLong()).isEqualTo(1L);
    }

    @Test
    void 없는_공연은_problem_json으로_EVENT_NOT_FOUND() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/events/999", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(response.getBody().get("code").asText()).isEqualTo("EVENT_NOT_FOUND");
    }

    @Test
    void 좌석_배치는_4구역_2000석이고_불변_캐시와_ETag가_붙는다() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/schedules/1/seats/layout", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("immutable");
        assertThat(response.getHeaders().getETag()).isNotNull();

        JsonNode sections = response.getBody().get("sections");
        assertThat(sections).hasSize(4);
        int total = 0;
        for (JsonNode section : sections) {
            assertThat(section.get("seatCount").asInt()).isEqualTo(500);
            total += section.get("seats").size();
        }
        assertThat(total).isEqualTo(2000);
        // 좌석 순서 계약: 첫 좌석은 1행 1열
        JsonNode first = sections.get(0).get("seats").get(0);
        assertThat(first.get("rowNo").asInt()).isEqualTo(1);
        assertThat(first.get("colNo").asInt()).isEqualTo(1);
    }

    @Test
    void 배치_ETag로_재요청하면_304() {
        ResponseEntity<String> first = rest.getForEntity("/api/schedules/1/seats/layout", String.class);
        String etag = first.getHeaders().getETag();

        HttpHeaders headers = new HttpHeaders();
        headers.setIfNoneMatch(etag);
        ResponseEntity<String> second = rest.exchange("/api/schedules/1/seats/layout",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(second.getBody()).isNull();
    }

    @Test
    void 좌석_상태_비트맵은_구역당_125바이트_총_500바이트_전석_빈자리다() {
        JsonNode body = rest.getForObject("/api/schedules/1/seats/status", JsonNode.class);

        JsonNode sections = body.get("sections");
        assertThat(sections).hasSize(4);
        int totalBytes = 0;
        for (JsonNode section : sections) {
            byte[] bitmap = Base64.getDecoder().decode(section.get("bitmap").asText());
            assertThat(bitmap).hasSize(125);
            totalBytes += bitmap.length;
            for (byte b : bitmap) {
                assertThat(b).isZero();   // 확정이 아직 없으니 전부 00(빈자리)
            }
        }
        assertThat(totalBytes).isEqualTo(500);
    }

    @Test
    void 없는_회차의_상태는_SCHEDULE_NOT_FOUND() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/schedules/999/seats/status", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("SCHEDULE_NOT_FOUND");
    }
}
