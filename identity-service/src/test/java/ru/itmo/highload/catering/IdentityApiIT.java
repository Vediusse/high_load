package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import ru.itmo.highload.catering.config.JwtProperties;
import ru.itmo.highload.catering.identity.dto.in.CreateUserRequest;
import ru.itmo.highload.catering.identity.dto.in.LoginRequest;
import ru.itmo.highload.catering.identity.dto.out.TokenResponse;
import ru.itmo.highload.catering.identity.entity.RoleCode;
import ru.itmo.highload.catering.identity.service.UserService;

@Testcontainers
@AutoConfigureWebTestClient
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = "spring.config.import=")
class IdentityApiIT {
    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    static final byte[] secret = new byte[32];
    static final UUID organizationId = UUID.randomUUID();
    static final HttpServer order;
    static volatile int organizationStatus = 200;
    static volatile boolean organizationActive = true;
    static volatile String organizationBody;
    static final AtomicInteger organizationCalls = new AtomicInteger();
    static volatile String receivedBearer;
    static volatile String receivedTrace;

    static {
        new java.security.SecureRandom().nextBytes(secret);
        try {
            order = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            order.createContext(
                    "/internal/v1/organizations/",
                    exchange -> {
                        organizationCalls.incrementAndGet();
                        receivedBearer = exchange.getRequestHeaders().getFirst("Authorization");
                        receivedTrace = exchange.getRequestHeaders().getFirst("X-Trace-Id");
                        String body =
                                organizationStatus == 200
                                        ? "{\"id\":\""
                                                + organizationId
                                                + "\",\"active\":"
                                                + organizationActive
                                                + "}"
                                        : "{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Организация"
                                              + " не найдена\",\"fieldErrors\":[],\"traceId\":\"identity-it\"}";
                        if (organizationBody != null) body = organizationBody;
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(organizationStatus, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            order.start();
        } catch (java.io.IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        ru.itmo.highload.common.security.TestTokens.register(properties);
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
        properties.add(
                "identity.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(secret));
        properties.add(
                "clients.order.url", () -> "http://127.0.0.1:" + order.getAddress().getPort());
    }

    @AfterAll
    static void close() {
        order.stop(0);
    }

    @Autowired WebTestClient http;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService users;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtProperties jwtProperties;
    @Autowired CircuitBreakerRegistry breakers;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    ru.itmo.highload.catering.identity.repository.UserRepository repository;

    String admin;
    static final String PASSWORD = "integration-password-42";

    @BeforeEach
    void reset() {
        jdbc.execute("truncate app_user cascade");
        users.bootstrap("supervisor", PASSWORD);
        organizationStatus = 200;
        organizationActive = true;
        organizationBody = null;
        organizationCalls.set(0);
        breakers.circuitBreaker("order").reset();
        admin = login("supervisor", PASSWORD);
    }

    WebTestClient.RequestHeadersSpec<?> get(String path, String token) {
        var request = http.get().uri(path).header("X-Trace-Id", "identity-it");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request;
    }

    JsonNode post(String path, Object body, String token, int status) {
        var request = http.post().uri(path).header("X-Trace-Id", "identity-it");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return request.bodyValue(body)
                .exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectHeader()
                .valueEquals("X-Trace-Id", "identity-it")
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    String login(String login, String password) {
        return post("/api/v1/auth/login", Map.of("login", login, "password", password), null, 200)
                .get("accessToken")
                .asText();
    }

    JsonNode create(String login, Set<String> roles, UUID org, int status) {
        Map<String, Object> body =
                new HashMap<>(Map.of("login", login, "password", PASSWORD, "roles", roles));
        if (org != null) body.put("organizationId", org);
        return post("/api/v1/users", body, admin, status);
    }

    JsonNode roles(String id, Set<String> roles, int status) {
        return http.put()
                .uri("/api/v1/users/" + id + "/roles")
                .header("Authorization", "Bearer " + admin)
                .bodyValue(Map.of("roles", roles))
                .exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    String signed(
            String source, java.util.function.Consumer<JWTClaimsSet.Builder> change, byte[] key)
            throws Exception {
        var builder = new JWTClaimsSet.Builder(SignedJWT.parse(source).getJWTClaimsSet());
        change.accept(builder);
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
        token.sign(new MACSigner(key));
        return token.serialize();
    }

    @Test
    void bootstrapUsesHashAndDoesNotResetExistingAccount() throws Exception {
        assertThat(jdbc.queryForObject("select count(*) from role", Integer.class)).isEqualTo(4);
        String hash = jdbc.queryForObject("select password_hash from app_user", String.class);
        assertThat(hash).startsWith("$2a$12$").doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, hash)).isTrue();
        users.bootstrap("another", "different-password");
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
                .isEqualTo(1);
        JWTClaimsSet claims = SignedJWT.parse(admin).getJWTClaimsSet();
        assertThat(claims.getStringListClaim("roles")).containsExactly("SUPERVISOR");
        assertThat(claims.getAudience()).containsExactly("corporate-catering-api");
        assertThat(claims.getClaims())
                .doesNotContainKeys("password", "passwordHash", "login", "organizationId");
        assertThat(claims.getExpirationTime().toInstant()).isAfter(Instant.now().plusSeconds(800));
        assertThat(SignedJWT.parse(login("supervisor", PASSWORD)).getJWTClaimsSet().getJWTID())
                .isNotEqualTo(claims.getJWTID());
    }

    @Test
    void invalidCredentialsHaveSameResponseAndBlockedTokensAreRejected() {
        var wrong =
                post(
                        "/api/v1/auth/login",
                        Map.of("login", "supervisor", "password", "bad-password"),
                        null,
                        401);
        var missing =
                post(
                        "/api/v1/auth/login",
                        Map.of("login", "missing", "password", PASSWORD),
                        null,
                        401);
        String id =
                get("/api/v1/me", admin)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody()
                        .get("id")
                        .asText();
        post("/api/v1/users/" + id + "/block", Map.of(), admin, 200);
        var blocked =
                post(
                        "/api/v1/auth/login",
                        Map.of("login", "supervisor", "password", PASSWORD),
                        null,
                        401);
        assertThat(wrong).isEqualTo(missing).isEqualTo(blocked);
        get("/api/v1/me", admin).exchange().expectStatus().isUnauthorized();
        users.bootstrap("supervisor", PASSWORD);
        assertThat(jdbc.queryForObject("select status from app_user", String.class))
                .isEqualTo("BLOCKED");
    }

    @Test
    void missingInvalidAndForgedTokensUseApiErrorWithTrace() {
        for (String token : Arrays.asList(null, "broken", "a.b.c")) {
            get("/api/v1/me", token)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectHeader()
                    .valueEquals("X-Trace-Id", "identity-it")
                    .expectHeader()
                    .valueEquals("WWW-Authenticate", "Bearer")
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("AUTHENTICATION_REQUIRED")
                    .jsonPath("$.traceId")
                    .isEqualTo("identity-it");
        }
        http.get()
                .uri("/api/v1/users")
                .header("X-User-Id", "supervisor")
                .header("X-Roles", "SUPERVISOR")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectHeader()
                .exists("X-Trace-Id");
    }

    @Test
    void signatureExpiryAndClaimsAreValidated() throws Exception {
        List<java.util.function.Consumer<JWTClaimsSet.Builder>> changes =
                List.of(
                        b -> b.expirationTime(Date.from(Instant.now().minusSeconds(90))),
                        b -> b.issueTime(Date.from(Instant.now().plusSeconds(90))),
                        b -> b.issuer("other"),
                        b -> b.audience("other"),
                        b -> b.subject("bad-uuid"),
                        b -> b.jwtID(null),
                        b -> b.notBeforeTime(Date.from(Instant.now().plusSeconds(90))),
                        b -> b.claim("roles", List.of("ROOT")),
                        b -> b.claim("roles", List.of()),
                        b -> b.claim("roles", "SUPERVISOR"),
                        b -> b.claim("roles", List.of(123)),
                        b -> b.claim("roles", List.of("SUPERVISOR", "SUPERVISOR")),
                        b -> b.claim("ver", -1),
                        b -> b.claim("ver", 0.5),
                        b -> b.claim("organizationId", UUID.randomUUID().toString()));
        for (var change : changes)
            get("/api/v1/me", signed(admin, change, secret))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
        byte[] wrongKey = new byte[32];
        new java.security.SecureRandom().nextBytes(wrongKey);
        get("/api/v1/me", signed(admin, b -> {}, wrongKey))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        var unsigned = new PlainJWT(SignedJWT.parse(admin).getJWTClaimsSet()).serialize();
        get("/api/v1/me", unsigned).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void supervisorCreatesReadsAndPaginatesWithoutSecrets() {
        var user = create("  Client.Manager  ", Set.of("CLIENT_MANAGER"), null, 201);
        assertThat(user.get("login").asText()).isEqualTo("client.manager");
        assertThat(user.toString()).doesNotContain("password", PASSWORD, "$2a$");
        get("/api/v1/users/" + user.get("id").asText(), admin)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json(user.toString());
        get("/api/v1/users?size=1", admin)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.hasNext")
                .isEqualTo(true)
                .jsonPath("$.items.length()")
                .isEqualTo(1);
        get("/api/v1/users?page=100&size=1", admin)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(0);
        get("/api/v1/users?size=51", admin).exchange().expectStatus().isBadRequest();
        get("/api/v1/users?page=-1", admin).exchange().expectStatus().isBadRequest();
        get("/api/v1/users/" + UUID.randomUUID(), admin).exchange().expectStatus().isNotFound();
        assertThat(
                        create("CLIENT.MANAGER", Set.of("CLIENT_MANAGER"), null, 409)
                                .get("code")
                                .asText())
                .isEqualTo("LOGIN_ALREADY_EXISTS");
        http.post()
                .uri("/api/v1/users")
                .header("Authorization", "Bearer " + admin)
                .bodyValue(
                        Map.of(
                                "login",
                                "location-test",
                                "password",
                                PASSWORD,
                                "roles",
                                List.of("SUPERVISOR")))
                .exchange()
                .expectStatus()
                .isCreated()
                .expectHeader()
                .valueMatches("Location", "/api/v1/users/.*");
    }

    @Test
    void onlySupervisorCanManageUsersWhileAllRolesCanReadMe() {
        for (String role :
                List.of("CLIENT_MANAGER", "KITCHEN_MANAGER", "ORGANIZATION_REPRESENTATIVE")) {
            String login = role.toLowerCase(Locale.ROOT);
            var user =
                    create(
                            login,
                            Set.of(role),
                            role.startsWith("ORGANIZATION") ? organizationId : null,
                            201);
            String token = login(login, PASSWORD), id = user.get("id").asText();
            get("/api/v1/me", token).exchange().expectStatus().isOk();
            get("/api/v1/users", token)
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("ACCESS_DENIED");
            get("/api/v1/users/" + id, token).exchange().expectStatus().isForbidden();
            post(
                    "/api/v1/users",
                    Map.of(
                            "login",
                            "forbidden",
                            "password",
                            PASSWORD,
                            "roles",
                            List.of("SUPERVISOR")),
                    token,
                    403);
            post("/api/v1/users/" + id + "/block", Map.of(), token, 403);
            http.put()
                    .uri("/api/v1/users/" + id + "/roles")
                    .header("Authorization", "Bearer " + token)
                    .bodyValue(Map.of("roles", List.of("SUPERVISOR")))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
    }

    @Test
    void roleChangesAndBlockingRevokeOldTokensButNoOpDoesNot() {
        var user = create("manager", Set.of("CLIENT_MANAGER"), null, 201);
        String id = user.get("id").asText();
        String old = login("manager", PASSWORD);
        roles(id, Set.of("CLIENT_MANAGER"), 200);
        get("/api/v1/me", old).exchange().expectStatus().isOk();
        roles(id, Set.of("KITCHEN_MANAGER"), 200);
        get("/api/v1/me", old).exchange().expectStatus().isUnauthorized();
        String fresh = login("manager", PASSWORD);
        get("/api/v1/me", fresh).exchange().expectStatus().isOk();
        post("/api/v1/users/" + id + "/block", Map.of(), admin, 200);
        long version =
                jdbc.queryForObject(
                        "select version from app_user where login='manager'", Long.class);
        post("/api/v1/users/" + id + "/block", Map.of(), admin, 200);
        assertThat(
                        jdbc.queryForObject(
                                "select version from app_user where login='manager'", Long.class))
                .isEqualTo(version);
    }

    @Test
    void representativeOrganizationIsVerifiedAndTokenOnlyCarriesEffectiveBinding()
            throws Exception {
        var user =
                create(
                        "representative",
                        Set.of("ORGANIZATION_REPRESENTATIVE"),
                        organizationId,
                        201);
        assertThat(receivedBearer).startsWith("Bearer ").isNotEqualTo("Bearer " + admin);
        assertThat(receivedTrace).isEqualTo("identity-it");
        String token = login("representative", PASSWORD), id = user.get("id").asText();
        assertThat(SignedJWT.parse(token).getJWTClaimsSet().getStringClaim("organizationId"))
                .isEqualTo(organizationId.toString());
        roles(id, Set.of("CLIENT_MANAGER"), 200);
        assertThat(SignedJWT.parse(login("representative", PASSWORD)).getJWTClaimsSet().getClaims())
                .doesNotContainKey("organizationId");
        roles(id, Set.of("ORGANIZATION_REPRESENTATIVE"), 200);
        var unbound = create("unbound", Set.of("CLIENT_MANAGER"), null, 201);
        assertThat(
                        roles(
                                        unbound.get("id").asText(),
                                        Set.of("ORGANIZATION_REPRESENTATIVE"),
                                        422)
                                .get("code")
                                .asText())
                .isEqualTo("ORGANIZATION_REQUIRED");
    }

    @Test
    void failedOrganizationChecksNeverSaveUser() {
        organizationStatus = 404;
        create("missing-org", Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 404);
        organizationStatus = 503;
        create("offline-org", Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 503);
        organizationStatus = 200;
        organizationActive = false;
        create("inactive-org", Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 422);
        organizationActive = true;
        create("wrong-response", Set.of("ORGANIZATION_REPRESENTATIVE"), UUID.randomUUID(), 503);
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void failedOrganizationCheckPreservesRolesAndPreviouslyIssuedToken() {
        var user = create("bound-manager", Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 201);
        String id = user.get("id").asText();
        roles(id, Set.of("CLIENT_MANAGER"), 200);
        String token = login("bound-manager", PASSWORD);
        long version = jdbc.queryForObject("select version from app_user where login='bound-manager'", Long.class);

        organizationStatus = 503;
        roles(id, Set.of("ORGANIZATION_REPRESENTATIVE"), 503);

        assertThat(jdbc.queryForObject("select version from app_user where login='bound-manager'", Long.class))
                .isEqualTo(version);
        get("/api/v1/me", token).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.roles[0]").isEqualTo("CLIENT_MANAGER");
    }

    @Test
    void malformedOrganizationResponsesAreUnavailableAndNeverCreateUsers() {
        for (String body :
                List.of(
                        "{}",
                        "null",
                        "{\"id\":\"" + organizationId + "\"}",
                        "<html>bad response</html>")) {
            organizationBody = body;
            assertThat(
                            create(
                                            "malformed-org",
                                            Set.of("ORGANIZATION_REPRESENTATIVE"),
                                            organizationId,
                                            503)
                                    .get("code")
                                    .asText())
                    .isEqualTo("DEPENDENCY_UNAVAILABLE");
        }
        breakers.circuitBreaker("order").reset();
        organizationStatus = 404;
        organizationBody = "<html>proxy route missing</html>";
        create("proxy-error", Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 503);
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void organizationCircuitOpensAndRecoversWithoutPartialWrites() {
        organizationStatus = 503;
        for (int i = 0; i < 4; i++) {
            create("outage-" + i, Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 503);
        }
        var circuit = breakers.circuitBreaker("order");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int calls = organizationCalls.get();
        create("fast-failure", Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 503);
        assertThat(organizationCalls.get()).isEqualTo(calls);
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
                .isEqualTo(1);

        organizationStatus = 200;
        // Exercise real half-open probes without a timing-dependent sleep in the test.
        circuit.transitionToHalfOpenState();
        create("recovered-first", Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 201);
        create("recovered-second", Set.of("ORGANIZATION_REPRESENTATIVE"), organizationId, 201);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void recognizedOrganizationErrorsPreserveStatusAndDoNotTripCircuit() {
        for (int i = 0; i < 8; i++) {
            organizationStatus = List.of(401, 403, 404).get(i % 3);
            String code =
                    switch (organizationStatus) {
                        case 401 -> "AUTHENTICATION_REQUIRED";
                        case 403 -> "ACCESS_DENIED";
                        default -> "RESOURCE_NOT_FOUND";
                    };
            organizationBody =
                    "{\"code\":\""
                            + code
                            + "\",\"message\":\"Отказ\",\"fieldErrors\":[],\"traceId\":\"remote\"}";
            assertThat(
                            create(
                                            "denied-" + i,
                                            Set.of("ORGANIZATION_REPRESENTATIVE"),
                                            organizationId,
                                            organizationStatus)
                                    .get("code")
                                    .asText())
                    .isEqualTo(code);
        }
        assertThat(breakers.circuitBreaker("order").getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breakers.circuitBreaker("order").getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void bearerAuthenticationDoesNotCreateOrReuseHttpSession() {
        get("/api/v1/me", null)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectCookie()
                .doesNotExist("SESSION");
        get("/api/v1/me", admin)
                .exchange()
                .expectStatus()
                .isOk()
                .expectCookie()
                .doesNotExist("SESSION");
        get("/api/v1/me", null).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void validationAndSecretDtoRedaction() {
        create("bad login", Set.of("CLIENT_MANAGER"), null, 400);
        create("manager", Set.of(), null, 400);
        create("manager", Set.of("ROOT"), null, 400);
        post(
                "/api/v1/users",
                Map.of(
                        "login",
                        "duplicates",
                        "password",
                        PASSWORD,
                        "roles",
                        List.of("CLIENT_MANAGER", "CLIENT_MANAGER")),
                admin,
                400);
        create("manager", Set.of("CLIENT_MANAGER"), organizationId, 400);
        create("manager", Set.of("ORGANIZATION_REPRESENTATIVE"), null, 400);
        for (String password : List.of("short", "я".repeat(40), "x".repeat(73)))
            post(
                    "/api/v1/users",
                    Map.of(
                            "login",
                            "invalid-password",
                            "password",
                            password,
                            "roles",
                            List.of("CLIENT_MANAGER")),
                    admin,
                    400);
        assertThat(new LoginRequest("x", PASSWORD).toString()).doesNotContain(PASSWORD);
        assertThat(
                        new CreateUserRequest("x", PASSWORD, List.of(RoleCode.CLIENT_MANAGER), null)
                                .toString())
                .doesNotContain(PASSWORD);
        assertThat(new TokenResponse("secret-token", "Bearer", Instant.now()).toString())
                .doesNotContain("secret-token");
        assertThat(jwtProperties.toString())
                .doesNotContain(Base64.getEncoder().encodeToString(secret));
    }

    @Test
    void bootstrapRequiresCredentialsOnlyForEmptyDatabase() {
        users.bootstrap(null, null);
        jdbc.execute("truncate app_user cascade");
        assertThatThrownBy(() -> users.bootstrap(null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> users.bootstrap("ok", "short"))
                .isInstanceOf(ru.itmo.highload.common.error.ApiException.class);
        users.bootstrap("  New.Admin  ", PASSWORD);
        assertThat(jdbc.queryForObject("select login from app_user", String.class))
                .isEqualTo("new.admin");
    }

    @Test
    void onlyLoginIsPublicAcrossIdentityControllers() {
        String id = UUID.randomUUID().toString();
        for (String path : List.of("/api/v1/me", "/api/v1/users", "/api/v1/users/" + id)) {
            get(path, null).exchange().expectStatus().isUnauthorized();
        }
        post("/api/v1/users", Map.of(), null, 401);
        post("/api/v1/users/" + id + "/block", Map.of(), null, 401);
        http.put().uri("/api/v1/users/" + id + "/roles").bodyValue(Map.of())
                .exchange().expectStatus().isUnauthorized();
        assertThat(login("supervisor", PASSWORD)).isNotBlank();
    }

    @Test
    void healthIsAnonymousButDocsAndInfoRequireAuthentication() {
        for (String path :
                List.of(
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness"))
            get(path, null).exchange().expectStatus().isOk();
        get("/v3/api-docs", null).exchange().expectStatus().isUnauthorized();
        get("/v3/api-docs", admin)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                .isEqualTo("bearer");
        var schema =
                get("/v3/api-docs", admin)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        assertThat(schema.get("paths").size()).isEqualTo(6);
        var operation = schema.at("/paths/~1api~1v1~1users/post");
        assertThat(operation.get("summary").asText()).isEqualTo("Создать пользователя");
        for (String status : List.of("201", "400", "401", "403", "409", "503")) {
            assertThat(operation.get("responses").has(status)).isTrue();
        }
        var loginSecurity = schema.at("/paths/~1api~1v1~1auth~1login/post/security");
        assertThat(loginSecurity.isArray()).isTrue();
        assertThat(loginSecurity.isEmpty()).isTrue();
        get("/actuator/info", admin).exchange().expectStatus().isOk();
        get("/actuator/env", null).exchange().expectStatus().isUnauthorized();
        get("/actuator/env", admin)
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("RESOURCE_NOT_FOUND");
        get("/api/v1/unknown", admin).exchange().expectStatus().isNotFound();
        get("/api/v1/auth/login", admin).exchange().expectStatus().isEqualTo(405);
    }

    @Test
    void concurrentBootstrapCreatesOneSupervisor() throws Exception {
        jdbc.execute("truncate app_user cascade");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls =
                    executor.invokeAll(
                            List.of(
                                    Executors.callable(() -> users.bootstrap("first", PASSWORD)),
                                    Executors.callable(() -> users.bootstrap("second", PASSWORD))));
            for (var call : calls) call.get();
        }
        assertThat(jdbc.queryForObject("select count(*) from app_user", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void ambiguousAuthorizationHeadersAreRejected() {
        http.get()
                .uri("/api/v1/me")
                .header("Authorization", "Bearer " + admin, "Bearer " + admin)
                .exchange()
                .expectStatus()
                .isUnauthorized();
    }

    @Test
    void databaseFailureDuringAuthenticationIsFailClosedAndNotBadCredentials() {
        org.mockito.Mockito.doThrow(
                        new org.springframework.dao.DataAccessResourceFailureException(
                                "test outage"))
                .when(repository)
                .findById(org.mockito.ArgumentMatchers.any(UUID.class));
        get("/api/v1/me", admin)
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectHeader()
                .valueEquals("X-Trace-Id", "identity-it")
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("DEPENDENCY_UNAVAILABLE");
    }

    @Test
    void concurrentDuplicateLoginHasOneWinnerAndStableConflict() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> create =
                    () ->
                            http.post()
                                    .uri("/api/v1/users")
                                    .header("Authorization", "Bearer " + admin)
                                    .bodyValue(
                                            Map.of(
                                                    "login",
                                                    "duplicate",
                                                    "password",
                                                    PASSWORD,
                                                    "roles",
                                                    List.of("CLIENT_MANAGER")))
                                    .exchange()
                                    .returnResult(JsonNode.class)
                                    .getStatus()
                                    .value();
            var calls = executor.invokeAll(List.of(create, create));
            assertThat(List.of(calls.get(0).get(), calls.get(1).get()))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from app_user where login='duplicate'",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentUsersKeepTheirOwnIdentity() throws Exception {
        var first = create("first-user", Set.of("CLIENT_MANAGER"), null, 201);
        var second = create("second-user", Set.of("KITCHEN_MANAGER"), null, 201);
        String firstToken = login("first-user", PASSWORD),
                secondToken = login("second-user", PASSWORD);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls =
                    executor.invokeAll(
                            List.of(
                                    Executors.callable(
                                            (Runnable)
                                                    () ->
                                                            get("/api/v1/me", firstToken)
                                                                    .exchange()
                                                                    .expectStatus()
                                                                    .isOk()
                                                                    .expectBody()
                                                                    .json(first.toString())),
                                    Executors.callable(
                                            (Runnable)
                                                    () ->
                                                            get("/api/v1/me", secondToken)
                                                                    .exchange()
                                                                    .expectStatus()
                                                                    .isOk()
                                                                    .expectBody()
                                                                    .json(second.toString()))));
            for (var call : calls) call.get();
        }
    }
    JsonNode exchangeToken(String external, int status) {
        return http.post().uri("/internal/v1/auth/exchange")
                .headers(headers -> headers.setBearerAuth(external))
                .header("X-Gateway-Credential", ru.itmo.highload.common.security.TestTokens.GATEWAY_SECRET)
                .exchange().expectStatus().isEqualTo(status).expectBody(JsonNode.class)
                .returnResult().getResponseBody();
    }

    @Test
    void exchangeChecksUserOnceAndInternalRequestsDoNotReadAuthenticationStateAgain() throws Exception {
        org.mockito.Mockito.clearInvocations(repository);
        String internal = exchangeToken(admin, 200).get("accessToken").asText();
        var token = SignedJWT.parse(internal);
        assertThat(token.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
        assertThat(token.getHeader().getType().toString()).isEqualTo(ru.itmo.highload.common.security.InternalJwt.TYPE);
        assertThat(token.getJWTClaimsSet().getSubject()).isEqualTo(SignedJWT.parse(admin).getJWTClaimsSet().getSubject());
        assertThat(token.getJWTClaimsSet().getExpirationTime().toInstant()).isBefore(Instant.now().plusSeconds(31));
        get("/api/v1/users", internal).exchange().expectStatus().isOk();
        get("/api/v1/users", internal).exchange().expectStatus().isOk();
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1)).findById(org.mockito.ArgumentMatchers.any(UUID.class));
    }

    @Test
    void exchangeIsGatewayOnlyAndCannotRenewInternalTokens() {
        http.post().uri("/internal/v1/auth/exchange").headers(h -> h.setBearerAuth(admin))
                .exchange().expectStatus().isForbidden();
        http.post().uri("/internal/v1/auth/exchange").headers(h -> h.setBearerAuth(admin))
                .header("X-Gateway-Credential", "wrong").exchange().expectStatus().isForbidden();
        String internal = exchangeToken(admin, 200).get("accessToken").asText();
        exchangeToken(internal, 401);
    }

    @Test
    void externalTokenCannotClaimToBeAnInternalAdmission() throws Exception {
        var forged = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(new JOSEObjectType(ru.itmo.highload.common.security.InternalJwt.TYPE)).build(),
                SignedJWT.parse(admin).getJWTClaimsSet());
        forged.sign(new MACSigner(secret));
        get("/api/v1/users", forged.serialize()).exchange().expectStatus().isUnauthorized();
        exchangeToken(forged.serialize(), 401);
    }

    @Test
    void blockingStopsNewAdmissionsButAlreadyAdmittedChainCanFinish() throws Exception {
        String internal = exchangeToken(admin, 200).get("accessToken").asText();
        users.block(UUID.fromString(SignedJWT.parse(admin).getJWTClaimsSet().getSubject()));
        exchangeToken(admin, 401);
        get("/api/v1/users", internal).exchange().expectStatus().isOk();
    }

    @Test
    void internalLifetimeCannotExceedExternalExpiryAndExchangeFailsClosedOnDatabaseOutage() throws Exception {
        String shortLived = signed(admin, claims -> claims.expirationTime(Date.from(Instant.now().plusSeconds(10))), secret);
        String internal = exchangeToken(shortLived, 200).get("accessToken").asText();
        assertThat(SignedJWT.parse(internal).getJWTClaimsSet().getExpirationTime())
                .isEqualTo(SignedJWT.parse(shortLived).getJWTClaimsSet().getExpirationTime());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("test outage"))
                .when(repository).findById(org.mockito.ArgumentMatchers.any(UUID.class));
        exchangeToken(admin, 503);
    }
}
