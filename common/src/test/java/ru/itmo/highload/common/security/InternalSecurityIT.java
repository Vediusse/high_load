package ru.itmo.highload.common.security;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@SpringBootTest(classes = InternalSecurityIT.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.application.name=order-service")
@AutoConfigureWebTestClient
class InternalSecurityIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Endpoint.class)
    static class Application { }
    @RestController
    static class Endpoint {
        @GetMapping({"/actor", "/actuator/info"})
        Mono<Map<String, String>> actor(@AuthenticationPrincipal Jwt actor) {
            return Mono.just(Map.of("id", actor.getSubject()));
        }
        @GetMapping("/supervisor")
        @org.springframework.security.access.prepost.PreAuthorize("hasRole('SUPERVISOR')")
        Mono<String> supervisor() { return Mono.just("ok"); }
        @GetMapping("/actuator/health") Mono<String> health() { return Mono.just("ok"); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) { TestTokens.register(properties); }
    @Autowired WebTestClient client;

    @Test void authenticatesLocallyAndAppliesRolesWithoutAnIdentityServer() {
        client.get().uri("/actor").header("Authorization", TestTokens.bearer()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.id").isEqualTo(TestTokens.SUBJECT);
        client.get().uri("/actuator/info").header("Authorization", TestTokens.bearer()).exchange().expectStatus().isOk();
        String manager = TestTokens.token(TestTokens.claims().claim("roles", List.of("CLIENT_MANAGER")).build());
        client.get().uri("/actuator/info").headers(h -> h.setBearerAuth(manager)).exchange()
                .expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
        client.get().uri("/supervisor").headers(h -> h.setBearerAuth(manager)).exchange()
                .expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test void rejectsMissingAmbiguousAndTamperedTokensWithTrace() {
        client.get().uri("/actor").header("X-Trace-Id", "auth-test").exchange()
                .expectStatus().isUnauthorized().expectHeader().valueEquals("WWW-Authenticate", "Bearer")
                .expectBody().jsonPath("$.traceId").isEqualTo("auth-test");
        client.get().uri("/actor").header("Authorization", TestTokens.bearer(), TestTokens.bearer()).exchange()
                .expectStatus().isUnauthorized();
        client.get().uri("/actor").header("Authorization", "Bearer " + TestTokens.token() + "broken").exchange()
                .expectStatus().isUnauthorized();
    }

    @Test void rejectsWrongAudienceIssuerLifetimeAndIdentityClaims() {
        List<Consumer<JwtClaimsSet.Builder>> invalid = List.of(
                c -> c.audience(List.of("another-service")), c -> c.issuer("external"),
                c -> c.subject("invalid"), c -> c.id("invalid"), c -> c.claim("ver", -1),
                c -> c.claim("ver", "1"), c -> c.claim("roles", List.of()),
                c -> c.claim("roles", List.of("UNKNOWN")), c -> c.claim("roles", List.of("SUPERVISOR", "SUPERVISOR")),
                c -> c.claim("roles", List.of(1)), c -> c.claim("organizationId", UUID.randomUUID().toString()),
                c -> c.claim("roles", List.of("ORGANIZATION_REPRESENTATIVE")),
                c -> c.expiresAt(Instant.now().plusSeconds(100)),
                c -> c.issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().minusSeconds(30)),
                c -> c.issuedAt(Instant.now().plusSeconds(10)).expiresAt(Instant.now().plusSeconds(20)),
                c -> c.notBefore(Instant.now().plusSeconds(20)));
        for (var change : invalid) {
            var claims = TestTokens.claims(); change.accept(claims);
            client.get().uri("/actor").headers(h -> h.setBearerAuth(TestTokens.token(claims.build())))
                    .exchange().expectStatus().isUnauthorized();
        }
        String representative = TestTokens.token(TestTokens.claims().claim("roles", List.of("ORGANIZATION_REPRESENTATIVE"))
                .claim("organizationId", UUID.randomUUID().toString()).build());
        client.get().uri("/actor").headers(h -> h.setBearerAuth(representative)).exchange().expectStatus().isOk();
    }

    @Test void invalidKeysAndUnsafeTimeConfigurationFailAtStartup() {
        assertThatThrownBy(() -> InternalJwt.publicKey("not-a-key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalJwtProperties(null, "issuer", Duration.ofSeconds(30), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalJwtProperties(TestTokens.publicKey(), "issuer", Duration.ofMinutes(5), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InternalJwtProperties(TestTokens.publicKey(), "issuer", Duration.ofSeconds(30), Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
