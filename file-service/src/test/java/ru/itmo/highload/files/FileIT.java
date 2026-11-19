package ru.itmo.highload.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import ru.itmo.highload.common.security.InternalJwt;
import ru.itmo.highload.common.security.InternalJwtProperties;
import ru.itmo.highload.common.security.TestTokens;
import ru.itmo.highload.files.adapters.out.persistence.LegacyStorageMigration;
import ru.itmo.highload.files.adapters.out.storage.S3BinaryStorage;
import ru.itmo.highload.files.domain.FileFailure;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "20s")
class FileIT {
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    static final UUID ORDER = UUID.randomUUID(), ORG = UUID.randomUUID();
    static final byte[] PDF = "%PDF-1.4\nfile test\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    static final String BUCKET = "file-integration";
    static final GenericContainer<?> S3 =
            new GenericContainer<>("pgsty/silo:RELEASE.2026-11-12T00-00-00Z")
                    .withEnv("MINIO_ROOT_USER", "integration-admin")
                    .withEnv("MINIO_ROOT_PASSWORD", "integration-password-only")
                    .withCommand("server", "/data", "--console-address", ":9001")
                    .withExposedPorts(9000)
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    static final S3Client OBJECTS;
    static final Path ROOT;
    static final HttpServer OWNER;
    static volatile String status = "CONFIRMED", mode = "ok", receivedBearer, receivedTrace;

    static {
        try {
            S3.start();
            OBJECTS =
                    S3Client.builder()
                            .endpointOverride(
                                    URI.create(
                                            "http://"
                                                    + S3.getHost()
                                                    + ":"
                                                    + S3.getMappedPort(9000)))
                            .forcePathStyle(true)
                            .region(Region.US_EAST_1)
                            .credentialsProvider(
                                    StaticCredentialsProvider.create(
                                            AwsBasicCredentials.create(
                                                    "integration-admin",
                                                    "integration-password-only")))
                            .build();
            OBJECTS.createBucket(request -> request.bucket(BUCKET));
            ROOT = Files.createTempDirectory("lab4-file-it-");
            OWNER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            OWNER.setExecutor(
                    Executors.newCachedThreadPool(r -> {
                        var t = new Thread(r);
                        t.setDaemon(true);
                        return t;
                    }));
            OWNER.createContext(
                    "/internal/v1/orders/",
                    exchange -> {
                        receivedBearer = exchange.getRequestHeaders().getFirst("Authorization");
                        receivedTrace = exchange.getRequestHeaders().getFirst("X-Trace-Id");
                        int code = 200;
                        String body = "";
                        try {
                            if (mode.equals("down")) throw new IllegalStateException();
                            var jwt =
                                    InternalJwt.decoder(
                                                    new InternalJwtProperties(
                                                            TestTokens.publicKey(),
                                                            "corporate-catering-internal",
                                                            Duration.ofSeconds(30),
                                                            Duration.ofSeconds(2)),
                                                    "order-service")
                                            .decode(receivedBearer.substring(7))
                                            .block(Duration.ofSeconds(2));
                            if (!exchange.getRequestURI()
                                    .getPath()
                                    .equals("/internal/v1/orders/" + ORDER + "/file-access"))
                                code = 404;
                            else if (jwt.getClaimAsStringList("roles")
                                            .contains("ORGANIZATION_REPRESENTATIVE")
                                    && !ORG.toString()
                                            .equals(jwt.getClaimAsString("organizationId")))
                                code = 404;
                            if (code == 200)
                                body =
                                        "{\"id\":\""
                                                + ORDER
                                                + "\",\"organizationId\":\""
                                                + ORG
                                                + "\",\"status\":\""
                                                + status
                                                + "\",\"version\":4}";
                        } catch (Exception error) {
                            code = 503;
                        }
                        if (code != 200)
                            body =
                                    "{\"code\":\""
                                            + (code == 404
                                                    ? "RESOURCE_NOT_FOUND"
                                                    : "DEPENDENCY_UNAVAILABLE")
                                            + "\",\"message\":\"Unavailable\",\"fieldErrors\":[],\"traceId\":\"file-it\"}";
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(code, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        exchange.close();
                    });
            OWNER.start();
            DB.start();
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        TestTokens.register(p);
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
        p.add("files.s3.endpoint", () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(9000));
        p.add("files.s3.bucket", () -> BUCKET);
        p.add("files.s3.access-key", () -> "integration-admin");
        p.add("files.s3.secret-key", () -> "integration-password-only");
        p.add("clients.order.url", () -> "http://127.0.0.1:" + OWNER.getAddress().getPort());
    }

    @Autowired WebTestClient http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void reset() throws Exception {
        jdbc.execute("truncate file_document,outbox_event,file_storage_migration");
        OBJECTS.listObjectsV2(request -> request.bucket(BUCKET))
                .contents()
                .forEach(object ->
                        OBJECTS.deleteObject(request -> request.bucket(BUCKET).key(object.key())));
        try (var paths = Files.list(ROOT)) {
            for (var path : paths.toList()) Files.delete(path);
        }
        status = "CONFIRMED";
        mode = "ok";
        receivedBearer = null;
        receivedTrace = null;
    }

    String own() {
        return TestTokens.bearerForOrganization(ORG);
    }

    WebTestClient.ResponseSpec upload(
            byte[] bytes, String mime, String name, String relation, String token) {
        var body = new MultipartBodyBuilder();
        body.part("orderId", ORDER.toString());
        body.part("relationType", relation);
        body.part("file", new ByteArrayResource(bytes))
                .filename(name)
                .contentType(MediaType.parseMediaType(mime));
        var request = http.post().uri("/api/v1/files").header("X-Trace-Id", "file-test");
        if (token != null) request.header("Authorization", token);
        return request.body(BodyInserters.fromMultipartData(body.build())).exchange();
    }

    JsonNode create() {
        return upload(PDF, "application/pdf", "../../source.pdf", "SPECIFICATION", own())
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    void unicodeDownloadNameUsesRfc8187WithoutEncodedWord() {
        String name = "Спецификация № 42.pdf";
        var file =
                upload(PDF, "application/pdf", name, "SPECIFICATION", own())
                        .expectStatus()
                        .isCreated()
                        .expectBody(JsonNode.class)
                        .returnResult()
                        .getResponseBody();
        http.get()
                .uri("/api/v1/files/" + file.get("id").asText())
                .header("Authorization", own())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value(
                        "Content-Disposition",
                        value -> {
                            assertThat(value)
                                    .startsWith("attachment; filename=\"document.pdf\";")
                                    .contains("filename*=UTF-8''")
                                    .doesNotContain("=?UTF-8");
                            assertThat(ContentDisposition.parse(value).getFilename())
                                    .isEqualTo(name);
                        })
                .expectBody()
                .consumeWith(
                        response -> assertThat(response.getResponseBody()).containsExactly(PDF));
    }

    @Test
    void roundTripUsesOpaqueStorageAndDeletionRetainsBytesButHidesTheFile() throws Exception {
        var file = create();
        String id = file.get("id").asText();
        assertThat(file.has("storageKey")).isFalse();
        assertThat(file.get("checksum").asText()).hasSize(64);
        assertThat(receivedBearer != null && receivedBearer.startsWith("Bearer ")).isTrue();
        assertThat(receivedTrace).isEqualTo("file-test");
        var stored = OBJECTS.listObjectsV2(request -> request.bucket(BUCKET)).contents();
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().key()).matches("documents/[0-9a-f-]{36}");
        http.get()
                .uri("/api/v1/files/" + id)
                .header("Authorization", own())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .contentType(MediaType.APPLICATION_PDF)
                .expectHeader()
                .valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader()
                .valueEquals("Content-Disposition", "attachment; filename=\"source.pdf\"")
                .expectBody()
                .consumeWith(
                        response -> assertThat(response.getResponseBody()).containsExactly(PDF));
        http.get()
                .uri("/api/v1/orders/" + ORDER + "/files")
                .header("Authorization", own())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.items.length()")
                .isEqualTo(1);
        http.get()
                .uri("/api/v1/orders/" + ORDER + "/files?size=51")
                .header("Authorization", own())
                .exchange()
                .expectStatus()
                .isBadRequest();
        for (int i = 0; i < 2; i++)
            http.delete()
                    .uri("/api/v1/files/" + id)
                    .header("Authorization", own())
                    .exchange()
                    .expectStatus()
                    .isNoContent();
        http.get()
                .uri("/api/v1/files/" + id)
                .header("Authorization", own())
                .exchange()
                .expectStatus()
                .isNotFound();
        assertThat(jdbc.queryForObject("select status from file_document", String.class))
                .isEqualTo("DELETED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class))
                .isEqualTo(2);
        assertThat(OBJECTS.listObjectsV2(request -> request.bucket(BUCKET)).contents()).hasSize(1);
    }

    @Test
    void swaggerUsesGatewayRelativeServer() {
        http.get()
                .uri("/v3/api-docs")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.servers[0].url")
                .isEqualTo("/")
                .jsonPath("$.components.securitySchemes.bearerAuth.scheme")
                .isEqualTo("bearer");
    }

    @Test
    void foreignOrganizationAndOtherOwnerCannotUseTheFileAndDependencyFailureIsNotSuccess() {
        var file = create();
        String id = file.get("id").asText();
        String foreign = TestTokens.bearerForOrganization(UUID.randomUUID());
        http.get().uri("/api/v1/files/" + id).exchange().expectStatus().isUnauthorized();
        http.get()
                .uri("/api/v1/files/" + id)
                .header("Authorization", foreign)
                .exchange()
                .expectStatus()
                .isNotFound();
        http.get()
                .uri("/api/v1/orders/" + ORDER + "/files")
                .header("Authorization", foreign)
                .exchange()
                .expectStatus()
                .isNotFound();
        String another =
                "Bearer "
                        + TestTokens.token(
                                TestTokens.claims()
                                        .subject(UUID.randomUUID().toString())
                                        .claim("roles", List.of("ORGANIZATION_REPRESENTATIVE"))
                                        .claim("organizationId", ORG.toString())
                                        .build());
        http.delete()
                .uri("/api/v1/files/" + id)
                .header("Authorization", another)
                .exchange()
                .expectStatus()
                .isNotFound();
        http.get()
                .uri("/api/v1/files/" + id)
                .header("Authorization", TestTokens.bearerWithRoles("SUPERVISOR"))
                .exchange()
                .expectStatus()
                .isForbidden();
        mode = "down";
        http.get()
                .uri("/api/v1/files/" + id)
                .header("Authorization", own())
                .exchange()
                .expectStatus()
                .isEqualTo(503);
        upload(PDF, "application/pdf", "file.pdf", "SPECIFICATION", own())
                .expectStatus()
                .isEqualTo(503);
        assertThat(jdbc.queryForObject("select count(*) from file_document", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void typeContentSizeEmptyAndOrderStatusAreValidated() {
        upload(PDF, "application/octet-stream", "x.exe", "SPECIFICATION", own())
                .expectStatus()
                .isEqualTo(415);
        upload(new byte[] {1, 2, 3}, "application/pdf", "x.pdf", "SPECIFICATION", own())
                .expectStatus()
                .isEqualTo(422);
        upload(new byte[0], "application/pdf", "x.pdf", "SPECIFICATION", own())
                .expectStatus()
                .isBadRequest();
        var large = new byte[1025];
        System.arraycopy(PDF, 0, large, 0, PDF.length);
        upload(large, "application/pdf", "x.pdf", "SPECIFICATION", own())
                .expectStatus()
                .isEqualTo(413);
        upload(PDF, "application/pdf", "x.pdf", "DISCREPANCY_ACT", own())
                .expectStatus()
                .isEqualTo(422);
        status = "DRAFT";
        upload(PDF, "application/pdf", "x.pdf", "SPECIFICATION", own())
                .expectStatus()
                .isEqualTo(422);
        status = "READY";
        upload(PDF, "application/pdf", "x.pdf", "DISCREPANCY_ACT", own())
                .expectStatus()
                .isCreated();
    }

    @Test
    void metadataRollbackRemovesOnlyNewBinaryAndChecksumRejectsTampering() throws Exception {
        jdbc.execute(
                "alter table outbox_event add constraint test_outbox_failure check(false) not valid");
        try {
            upload(PDF, "application/pdf", "x.pdf", "SPECIFICATION", own())
                    .expectStatus()
                    .isEqualTo(409);
        } finally {
            jdbc.execute("alter table outbox_event drop constraint test_outbox_failure");
        }
        assertThat(jdbc.queryForObject("select count(*) from file_document", Integer.class))
                .isZero();
        assertThat(OBJECTS.listObjectsV2(request -> request.bucket(BUCKET)).contents()).isEmpty();
        var file = create();
        UUID key = jdbc.queryForObject("select storage_key from file_document", UUID.class);
        byte[] corrupted = PDF.clone();
        corrupted[corrupted.length - 1] ^= 1;
        OBJECTS.putObject(
                request -> request.bucket(BUCKET).key(S3BinaryStorage.objectKey(key)),
                RequestBody.fromBytes(corrupted));
        http.get()
                .uri("/api/v1/files/" + file.get("id").asText())
                .header("Authorization", own())
                .exchange()
                .expectStatus()
                .isEqualTo(500)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("FILE_INTEGRITY_ERROR");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void legacyMigrationChecksEveryObjectAndIsRestartSafe() throws Exception {
        create();
        UUID key = jdbc.queryForObject("select storage_key from file_document", UUID.class);
        OBJECTS.deleteObject(request -> request.bucket(BUCKET).key(S3BinaryStorage.objectKey(key)));
        Files.write(ROOT.resolve(key.toString()), new byte[] {1, 2, 3});
        var migration =
                new LegacyStorageMigration(
                        jdbc,
                        OBJECTS,
                        BUCKET,
                        new S3BinaryStorage(OBJECTS, BUCKET, 10485760),
                        ROOT,
                        10485760);
        assertThatThrownBy(migration::run).isInstanceOf(FileFailure.class);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from file_storage_migration", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from file_document", Integer.class))
                .isEqualTo(1);
        Files.write(ROOT.resolve(key.toString()), PDF);
        assertThat(migration.run()).isEqualTo(1);
        Files.delete(ROOT.resolve(key.toString()));
        assertThat(migration.run()).isEqualTo(1);
        assertThat(
                        OBJECTS.getObjectAsBytes(request ->
                                        request.bucket(BUCKET).key(S3BinaryStorage.objectKey(key)))
                                .asByteArray())
                .isEqualTo(PDF);
    }

    @Test
    void storageOutageIsBoundedAndDoesNotCommitMetadata() {
        try {
            S3.getDockerClient().pauseContainerCmd(S3.getContainerId()).exec();
            upload(PDF, "application/pdf", "x.pdf", "SPECIFICATION", own())
                    .expectStatus()
                    .isEqualTo(503);
            assertThat(jdbc.queryForObject("select count(*) from file_document", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class))
                    .isZero();
        } finally {
            S3.getDockerClient().unpauseContainerCmd(S3.getContainerId()).exec();
        }
        create();
    }

    @Test
    void onlySupervisorCanInspectRuntimePolicy() {
        http.get()
                .uri("/api/v1/files/settings")
                .header("Authorization", own())
                .exchange()
                .expectStatus()
                .isForbidden();
        http.get()
                .uri("/api/v1/files/settings")
                .header("Authorization", TestTokens.bearerWithRoles("SUPERVISOR"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.limits.maxBytes")
                .isEqualTo(1024);
    }

    @AfterAll
    static void closeStorage() {
        OBJECTS.close();
        S3.stop();
        OWNER.stop(0);
    }
}
