package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
class WalkingSkeletonIT extends AbstractPostgresIT {

    @Autowired
    WebTestClient client;

    @Autowired
    ObjectMapper objectMapper;


    @Test
    void connectsToPostgresAndAppliesFlywayMigration() {
        Integer databaseResult = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        Boolean migrationSucceeded = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '1'",
                Boolean.class);
        String schemaComment = jdbcTemplate.queryForObject(
                "SELECT obj_description('public'::regnamespace, 'pg_namespace')",
                String.class);

        assertThat(databaseResult).isEqualTo(1);
        assertThat(migrationSucceeded).isTrue();
        assertThat(schemaComment).isEqualTo("Order service schema");
    }

    @Test
    void exposesReadyHealthEndpoint() {
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void exposesActualOpenApiContractAndSwaggerUi() throws Exception {
        JsonNode document = client.get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
        assertThat(document.path("openapi").asText()).startsWith("3.");
        assertThat(document.at("/info/title").asText()).isEqualTo("Corporate Catering API");
        assertThat(document.at("/paths/~1api~1v1~1organizations/post/responses/201/description").asText())
                .isEqualTo("Организация создана");
        assertThat(document.at("/paths/~1api~1v1~1orders/get/responses/200/headers/X-Total-Count/description")
                .asText()).contains("Общее количество заказов");
        assertThat(document.at("/paths/~1api~1v1~1orders~1{id}~1confirm/post/responses/409/content/application~1json/schema/$ref")
                .asText()).isEqualTo("#/components/schemas/ApiError");
        assertThat(document.at("/components/schemas/ApiError/properties/traceId").isObject()).isTrue();
        assertThat(document.at("/paths/~1api~1v1~1orders/get/parameters").toString())
                .contains("X-Trace-Id");
    }

}
