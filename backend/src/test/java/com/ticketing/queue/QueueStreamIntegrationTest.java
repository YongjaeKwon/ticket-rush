package com.ticketing.queue;

import com.ticketing.queue.application.port.in.AdmitWaitingUseCase;
import com.ticketing.queue.application.port.in.EnterQueueUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** SSE 스트림이 순번을 push하고 입장 시 입장권과 함께 닫히는지 실제 HTTP로 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"queue.admission.enabled=false", "hold-expiry.enabled=false"})
@Testcontainers
class QueueStreamIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Container
    @ServiceConnection
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @LocalServerPort
    int port;

    @Autowired
    EnterQueueUseCase enterQueue;

    @Autowired
    AdmitWaitingUseCase admitWaiting;

    @Autowired
    StringRedisTemplate redisTemplate;

    /** 조건이 참이 될 때까지 최대 timeoutSeconds 동안 기다린다. */
    private void until(java.util.function.BooleanSupplier condition, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("시간 안에 조건이 충족되지 않았습니다");
            }
            Thread.sleep(200);
        }
    }

    @Test
    void 스트림은_순번을_push하다가_입장되면_입장권과_함께_닫힌다() throws Exception {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();
        enterQueue.enter(1L, "stream-user");

        ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
        HttpClient client = HttpClient.newHttpClient();
        CompletableFuture<Void> done = client.sendAsync(
                        HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + port
                                        + "/api/schedules/1/queue/stream?userId=stream-user")).build(),
                        HttpResponse.BodyHandlers.fromLineSubscriber(new java.util.concurrent.Flow.Subscriber<String>() {
                            @Override
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                s.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(String line) {
                                lines.add(line);
                            }

                            @Override
                            public void onError(Throwable t) {
                            }

                            @Override
                            public void onComplete() {
                            }
                        }))
                .thenApply(r -> null);

        // 대기 중 상태가 먼저 흘러온다 (순번 1, admitted false)
        until(() -> lines.stream().anyMatch(
                l -> l.contains("\"position\":1") && l.contains("\"admitted\":false")), 5);

        admitWaiting.admitNext();   // 입장 처리

        // 입장 이벤트(admitted true + 토큰)가 오고 스트림이 정상 종료된다
        until(() -> lines.stream().anyMatch(
                l -> l.contains("\"admitted\":true") && l.contains("token")), 5);
        done.get(5, TimeUnit.SECONDS);

        List<String> events = List.copyOf(lines);
        assertThat(events).anyMatch(l -> l.startsWith("event:queue-status") || l.contains("queue-status"));
    }
}
