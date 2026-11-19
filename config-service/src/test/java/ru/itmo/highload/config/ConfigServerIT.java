package ru.itmo.highload.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class ConfigServerIT {
    // A real committed Git repository: the tests need no network or GitHub credentials.
    private static final Path REPOSITORY = configurationRepository();

    @DynamicPropertySource
    static void repository(DynamicPropertyRegistry properties) {
        properties.add("spring.cloud.config.server.git.uri", () -> REPOSITORY.toUri().toString());
        properties.add("spring.cloud.config.server.git.default-label", () -> "main");
        properties.add("management.server.port", () -> "0");
        properties.add("CONFIG_ADMIN_USER", () -> "config-test-admin");
        properties.add("CONFIG_ADMIN_PASSWORD", () -> "local-test-password-only");
    }

    @Autowired TestRestTemplate http;
    @LocalManagementPort int managementPort;

    @Test
    void managementPlaneRequiresAnOperatorAndDoesNotExposeBusEnv() {
        String base = "http://localhost:" + managementPort;
        assertThat(
                        http.exchange(
                                        base + "/actuator/busrefresh",
                                        HttpMethod.POST,
                                        HttpEntity.EMPTY,
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(
                        http.withBasicAuth("config-test-admin", "local-test-password-only")
                                .getForEntity(base + "/actuator/info", String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(200);
        assertThat(
                        http.withBasicAuth("config-test-admin", "wrong-password")
                                .getForEntity(base + "/actuator/info", String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(401);
        assertThat(
                        http.withBasicAuth("config-test-admin", "local-test-password-only")
                                .exchange(
                                        base + "/actuator/busenv",
                                        HttpMethod.POST,
                                        HttpEntity.EMPTY,
                                        String.class)
                                .getStatusCode()
                                .value())
                .isEqualTo(403);
    }

    @Test
    void servesSharedAndApplicationConfiguration() {
        var result = http.getForEntity("/order-service/default", String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody())
                .contains("spring.datasource.url", "info.configuration.source", "config-service");
        assertThat(http.getForObject("/gateway-service/default", String.class))
                .contains("lb://order-service", "spring.cloud.gateway.server.webflux.routes");
    }

    @Test
    void selectsServiceProfileAndReturnsGitRevision() {
        var defaultConfig = http.getForObject("/order-service/default/main", String.class);
        var devConfig = http.getForObject("/order-service/dev/main", String.class);
        assertThat(defaultConfig)
                .contains("jdbc:postgresql://order-postgres:5432/orders", "\"label\":\"main\"")
                .doesNotContain("jdbc:postgresql://localhost:5432/orders_dev");
        assertThat(devConfig)
                .contains(
                        "jdbc:postgresql://localhost:5432/orders_dev", "info.configuration.source")
                .containsPattern("\"version\":\"[0-9a-f]{40}\"");
        assertThat(http.getForObject("/gateway-service/dev/main", String.class))
                .doesNotContain("spring.datasource.url");
    }

    @Test
    void servesNewGitCommitWithoutRebuildingConfigServer() throws IOException, GitAPIException {
        assertThat(http.getForObject("/catalog-service/default/main", String.class))
                .contains("initial-menu");
        Files.writeString(
                REPOSITORY.resolve("catalog-service/application.yml"),
                "menu.title: updated-menu\n");
        try (Git git = Git.open(REPOSITORY.toFile())) {
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("Update menu configuration")
                    .setAuthor("Config Test", "config-test@example.invalid")
                    .setCommitter("Config Test", "config-test@example.invalid")
                    .call();
        }
        assertThat(http.getForObject("/catalog-service/default/main", String.class))
                .contains("updated-menu")
                .doesNotContain("initial-menu");
    }

    private static Path configurationRepository() {
        try {
            Path directory = Files.createTempDirectory("config-server-git-test-").toRealPath();
            Map<String, String> files =
                    Map.of(
                            "application.yml", "info.configuration.source: config-service\n",
                            "order-service/application.yml",
                                    "spring.datasource.url: jdbc:postgresql://order-postgres:5432/orders\n",
                            "order-service/application-dev.yml",
                                    "spring.datasource.url: jdbc:postgresql://localhost:5432/orders_dev\n",
                            "gateway-service/application.yml",
                                    "spring.cloud.gateway.server.webflux.routes[0].uri: lb://order-service\n",
                            "catalog-service/application.yml", "menu.title: initial-menu\n");
            for (var file : files.entrySet()) {
                Path path = directory.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
            try (Git git =
                    Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
                git.add().addFilepattern(".").call();
                git.commit()
                        .setMessage("Initial configuration")
                        .setAuthor("Config Test", "config-test@example.invalid")
                        .setCommitter("Config Test", "config-test@example.invalid")
                        .call();
            }
            return directory;
        } catch (IOException | GitAPIException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
