package ru.itmo.highload.gateway;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {"spring.cloud.config.enabled=false", "spring.config.import=", "eureka.client.enabled=false"})
@AutoConfigureWebTestClient
class GatewayIT {
    static final HttpServer backend;
    static {
        try {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.createContext("/api/v1/echo", request -> {
                byte[] body = ("{\"traceId\":\"" + request.getRequestHeaders().getFirst("X-Trace-Id") + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", "application/json");
                request.getResponseHeaders().set("X-Trace-Id", request.getRequestHeaders().getFirst("X-Trace-Id"));
                request.sendResponseHeaders(201, body.length);
                request.getResponseBody().write(body);
                request.close();
            });
            backend.start();
        } catch (java.io.IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void routes(DynamicPropertyRegistry registry) {
        String prefix = "spring.cloud.gateway.server.webflux.routes";
        registry.add(prefix + "[0].id", () -> "test-backend");
        registry.add(prefix + "[0].uri", () -> "http://127.0.0.1:" + backend.getAddress().getPort());
        registry.add(prefix + "[0].predicates[0]", () -> "Path=/api/v1/echo");
        registry.add(prefix + "[1].id", () -> "missing-service");
        registry.add(prefix + "[1].uri", () -> "lb://unavailable-service");
        registry.add(prefix + "[1].predicates[0]", () -> "Path=/api/v1/unavailable");
    }
    @AfterAll static void stop() { backend.stop(0); }
    @Autowired WebTestClient http;

    @Test void forwardsStatusBodyAndTrace() {
        http.get().uri("/api/v1/echo").header("X-Trace-Id", "test-trace").exchange()
                .expectStatus().isCreated().expectHeader().valueEquals("X-Trace-Id", "test-trace")
                .expectBody().jsonPath("$.traceId").isEqualTo("test-trace");
    }
    @Test void replacesUnsafeTraceAndGeneratesMissingTrace() {
        http.get().uri("/api/v1/echo").header("X-Trace-Id", "bad trace!").exchange()
                .expectStatus().isCreated().expectBody().jsonPath("$.traceId")
                .value(value -> org.assertj.core.api.Assertions.assertThat(value.toString()).matches("[a-f0-9-]{36}"));
        http.get().uri("/api/v1/echo").exchange().expectStatus().isCreated()
                .expectHeader().exists("X-Trace-Id");
    }
    @Test void hidesInternalRoutesAndReportsDependencyFailure() {
        http.get().uri("/internal/v1/orders").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        http.get().uri("/api/v1/unavailable").header("X-Trace-Id", "failed-call").exchange()
                .expectStatus().isEqualTo(503).expectBody()
                .jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE")
                .jsonPath("$.traceId").isEqualTo("failed-call");
    }
}
