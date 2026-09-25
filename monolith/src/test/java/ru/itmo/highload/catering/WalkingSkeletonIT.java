package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WalkingSkeletonIT extends AbstractPostgresIT {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @LocalServerPort
    int port;

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
        assertThat(schemaComment).isEqualTo("Corporate catering application schema");
    }

    @Test
    void exposesReadyHealthEndpoint() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                url("/actuator/health/readiness"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void exposesActualOpenApiContractAndSwaggerUi() throws Exception {
        ResponseEntity<String> openApi = restTemplate.getForEntity(url("/v3/api-docs"), String.class);
        ResponseEntity<String> swaggerUi = restTemplate.getForEntity(url("/swagger-ui/index.html"), String.class);

        assertThat(openApi.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode document = objectMapper.readTree(openApi.getBody());
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
        assertThat(swaggerUi.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
