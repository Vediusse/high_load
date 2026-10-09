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
    static final java.util.concurrent.atomic.AtomicInteger exchanges = new java.util.concurrent.atomic.AtomicInteger();
    static volatile int identityStatus = 200;
    static volatile boolean abortExchange;
    static volatile String forwardedBearer;
    static volatile String forgedRole;
    static volatile String receivedCredential;
    static volatile String exchangeBody;

    static {
        try {
            backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            backend.createContext("/internal/v1/auth/exchange", request -> {
                exchanges.incrementAndGet();
                if (abortExchange) { request.close(); return; }
                receivedCredential = request.getRequestHeaders().getFirst("X-Gateway-Credential");
                String external = request.getRequestHeaders().getFirst("Authorization");
                int status = "Bearer external-token".equals(external) ? identityStatus : 401;
                String token = ru.itmo.highload.common.security.TestTokens.token();
                String response = status == 200 ? "{\"accessToken\":\"" + token + "\",\"tokenType\":\"Bearer\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}" : "{}";
                if (exchangeBody != null) response = exchangeBody;
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                request.getResponseHeaders().set("Content-Type", "application/json");
                request.sendResponseHeaders(status, bytes.length);
                request.getResponseBody().write(bytes); request.close();
            });
            backend.createContext("/api/v1/echo", request -> {
                forwardedBearer = request.getRequestHeaders().getFirst("Authorization");
                forgedRole = request.getRequestHeaders().getFirst("X-Roles");
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
        ru.itmo.highload.common.security.TestTokens.register(registry);
        registry.add("spring.cloud.discovery.client.simple.instances.identity-service[0].uri",
                () -> "http://127.0.0.1:" + backend.getAddress().getPort());
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
    @org.junit.jupiter.api.BeforeEach void reset() {
        identityStatus = 200; exchangeBody = null; abortExchange = false; exchanges.set(0);
        http = http.mutate().defaultHeader("Authorization", "Bearer external-token").build();
    }


    @Test void forwardsStatusBodyAndTrace() {
        http.get().uri("/api/v1/echo").header("X-Trace-Id", "test-trace").exchange()
                .expectStatus().isCreated().expectHeader().valueEquals("X-Trace-Id", "test-trace")
                .expectBody().jsonPath("$.traceId").isEqualTo("test-trace");
        org.assertj.core.api.Assertions.assertThat(exchanges.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(forwardedBearer).startsWith("Bearer ").isNotEqualTo("Bearer external-token");
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
    @Test void stripsForgedHeadersAndUsesOnlyItsOwnExchangeCredential() {
        http.get().uri("/api/v1/echo").header("X-Roles", "SUPERVISOR")
                .header("X-Gateway-Credential", "forged").exchange().expectStatus().isCreated();
        org.assertj.core.api.Assertions.assertThat(forgedRole).isNull();
        org.assertj.core.api.Assertions.assertThat(receivedCredential)
                .isEqualTo(ru.itmo.highload.common.security.TestTokens.GATEWAY_SECRET);
    }
    @Test void rejectsAnonymousAndInternalTokensAtExternalBoundary() {
        var anonymous = http.mutate().defaultHeaders(headers -> headers.clear()).build();
        anonymous.get().uri("/api/v1/echo").exchange().expectStatus().isUnauthorized();
        org.assertj.core.api.Assertions.assertThat(exchanges.get()).isZero();
        http.get().uri("/api/v1/echo").headers(headers -> headers.setBearerAuth(
                ru.itmo.highload.common.security.TestTokens.token())).exchange().expectStatus().isUnauthorized();
    }
    @Test void connectionResetDoesNotRepeatAdmission() {
        abortExchange = true;
        forwardedBearer = null;
        http.get().uri("/api/v1/echo").exchange().expectStatus().isEqualTo(503);
        org.assertj.core.api.Assertions.assertThat(exchanges.get()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(forwardedBearer).isNull();
    }

    @Test void identityRejectionAndFailureHaveDifferentStatusesAndNeverCallBackend() {
        forwardedBearer = null; identityStatus = 401;
        http.get().uri("/api/v1/echo").exchange().expectStatus().isUnauthorized();
        identityStatus = 503;
        http.get().uri("/api/v1/echo").exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("DEPENDENCY_UNAVAILABLE");
        org.assertj.core.api.Assertions.assertThat(forwardedBearer).isNull();
    }
    @Test void malformedSuccessfulExchangeIsDependencyFailure() {
        exchangeBody = "{}";
        http.get().uri("/api/v1/echo").exchange().expectStatus().isEqualTo(503);
        exchangeBody = "{\"accessToken\":\"broken\",\"tokenType\":\"Bearer\"}";
        http.get().uri("/api/v1/echo").exchange().expectStatus().isEqualTo(503);
    }
}
