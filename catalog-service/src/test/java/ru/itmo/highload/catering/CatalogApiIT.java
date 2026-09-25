package ru.itmo.highload.catering;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import reactor.test.StepVerifier;
import ru.itmo.highload.catering.catalog.entity.Dish;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.data.relational.core.query.Query.query;
import static org.springframework.data.relational.core.query.Criteria.where;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
@AutoConfigureWebTestClient
class CatalogApiIT {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.11-alpine3.24");
    static { POSTGRES.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + POSTGRES.getDatabaseName());
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }
    @Autowired WebTestClient client;
    @Autowired R2dbcEntityTemplate template;
    @BeforeEach void clean() {
        template.getDatabaseClient().sql("TRUNCATE dish_category, dish, category CASCADE").fetch().rowsUpdated().block();
    }
    JsonNode post(String path, Object body) {
        return client.post().uri(path).bodyValue(body).exchange().expectStatus().isCreated()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
    }
    UUID category(String name) { return UUID.fromString(post("/api/v1/categories", Map.of("name", name)).get("id").asText()); }
    Map<String, Object> payload(String name, String price, Set<UUID> categories) {
        return Map.of("name", name, "description", "Описание", "currentPrice", new BigDecimal(price), "categoryIds", categories);
    }
    UUID dish(String name, String price, Set<UUID> categories) {
        return UUID.fromString(post("/api/v1/dishes", payload(name, price, categories)).get("id").asText());
    }
    @Test void crudRelationsAndCursorPaginationPreserveOriginalContract() {
        UUID soups = category("Супы"), mains = category("Основные блюда"), unused = category("Десерты");
        UUID borsch = dish("Борщ", "180.00", Set.of(soups));
        UUID cutlet = dish("Котлета", "320.00", Set.of(mains));
        UUID lunch = dish("Обед", "450.00", Set.of(soups, mains));
        JsonNode first = client.get().uri("/api/v1/dishes?limit=2").exchange().expectStatus().isOk()
                .expectHeader().doesNotExist("X-Total-Count").expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("hasNext").asBoolean()).isTrue();
        assertThat(first.has("total")).isFalse();
        client.get().uri("/api/v1/dishes?limit=2&afterId=" + first.get("nextCursor").asText()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.hasNext").isEqualTo(false).jsonPath("$.nextCursor").isEmpty();
        client.get().uri("/api/v1/dishes?categoryId=" + soups).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[*].id").value(org.hamcrest.Matchers.containsInAnyOrder(borsch.toString(), lunch.toString()));
        client.put().uri("/api/v1/dishes/" + cutlet).bodyValue(payload("Котлета с пюре", "350.00", Set.of(soups, mains)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.name").isEqualTo("Котлета с пюре").jsonPath("$.currentPrice").isEqualTo(350.00)
                .jsonPath("$.categoryIds.length()").isEqualTo(2);
        client.delete().uri("/api/v1/categories/" + soups).exchange().expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("CATEGORY_IN_USE");
        client.delete().uri("/api/v1/dishes/" + borsch).exchange().expectStatus().isNoContent();
        client.delete().uri("/api/v1/dishes/" + borsch).exchange().expectStatus().isNoContent();
        client.get().uri("/api/v1/dishes/" + borsch).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.active").isEqualTo(false).jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.categories").doesNotExist().jsonPath("$.hibernateLazyInitializer").doesNotExist();
        client.get().uri("/api/v1/dishes?limit=50").exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(2);
        client.put().uri("/api/v1/categories/" + unused).bodyValue(Map.of("name", "Выпечка"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.name").isEqualTo("Выпечка");
        client.delete().uri("/api/v1/categories/" + unused).exchange().expectStatus().isNoContent();
        client.get().uri("/api/v1/categories?page=0&size=1").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items.length()").isEqualTo(1).jsonPath("$.hasNext").isEqualTo(true);
        client.get().uri("/api/v1/categories?page=3&size=1").exchange().expectStatus().isOk().expectBody().jsonPath("$.items").isEmpty();
    }
    @Test void stableErrorsAndTraceId() {
        UUID soups = category("Супы"), other = category("Обеды");
        client.post().uri("/api/v1/categories").bodyValue(Map.of("name", " Супы ")).exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("CATEGORY_NAME_CONFLICT");
        client.put().uri("/api/v1/categories/" + other).bodyValue(Map.of("name", "Супы")).exchange()
                .expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("CATEGORY_NAME_CONFLICT");
        client.post().uri("/api/v1/dishes").header("X-Trace-Id", "catalog-check").bodyValue(payload("Борщ", "0.00", Set.of()))
                .exchange().expectStatus().isBadRequest().expectHeader().valueEquals("X-Trace-Id", "catalog-check")
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED").jsonPath("$.traceId").isEqualTo("catalog-check")
                .jsonPath("$.fieldErrors[0].field").isEqualTo("currentPrice");
        client.post().uri("/api/v1/dishes").bodyValue(payload("Щи", "190.00", Set.of(UUID.randomUUID())))
                .exchange().expectStatus().isNotFound();
        for (String path : List.of("/api/v1/dishes?limit=51", "/api/v1/categories?size=0", "/api/v1/categories?size=51", "/api/v1/categories?page=-1")) {
            client.get().uri(path).exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
        }
        client.get().uri("/api/v1/dishes?afterId=bad").exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.fieldErrors[0].field").isEqualTo("afterId");
        client.post().uri("/api/v1/dishes").contentType(MediaType.APPLICATION_JSON).bodyValue("{")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("MALFORMED_JSON");
        client.get().uri("/api/v1/dishes/" + UUID.randomUUID()).exchange().expectStatus().isNotFound();
        client.delete().uri("/api/v1/categories/" + UUID.randomUUID()).exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/dishes?categoryId=" + UUID.randomUUID()).exchange().expectStatus().isNotFound();
        client.get().uri("/api/v1/dishes").header("X-Trace-Id", "unsafe trace").exchange()
                .expectStatus().isOk().expectHeader().value("X-Trace-Id", value -> assertThat(UUID.fromString(value)).isNotNull());
    }
    @Test void batchSnapshotsAreCompleteAndRejectMissingOrInactiveDishes() {
        UUID first = dish("Борщ", "180.00", Set.of()), second = dish("Суп", "100.00", Set.of());
        client.post().uri("/internal/v1/dishes/snapshots").bodyValue(Map.of("ids", List.of(first, second)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].active").isEqualTo(true);
        client.post().uri("/internal/v1/dishes/snapshots").bodyValue(Map.of("ids", List.of()))
                .exchange().expectStatus().isOk().expectBody().json("[]");
        client.post().uri("/internal/v1/dishes/snapshots").bodyValue(Map.of("ids", List.of(first, UUID.randomUUID())))
                .exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        client.delete().uri("/api/v1/dishes/" + second).exchange().expectStatus().isNoContent();
        client.post().uri("/internal/v1/dishes/snapshots").bodyValue(Map.of("ids", List.of(first, second)))
                .exchange().expectStatus().isEqualTo(422).expectBody().jsonPath("$.code").isEqualTo("DISH_INACTIVE");
    }
    @Test void databaseConstraintsAndOptimisticLockingSurviveR2dbcMigration() {
        UUID id = dish("Борщ", "180.00", Set.of());
        Dish first = template.selectOne(query(where("id").is(id)), Dish.class).block();
        Dish stale = template.selectOne(query(where("id").is(id)), Dish.class).block();
        first.update("Щи", "", new BigDecimal("190.00"), Set.of());
        StepVerifier.create(template.update(first)).expectNextCount(1).verifyComplete();
        stale.deactivate();
        StepVerifier.create(template.update(stale)).expectError(org.springframework.dao.OptimisticLockingFailureException.class).verify();
        StepVerifier.create(template.getDatabaseClient().sql("UPDATE dish SET current_price = -1 WHERE id = :id")
                .bind("id", id).fetch().rowsUpdated()).expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
        StepVerifier.create(template.getDatabaseClient().sql("INSERT INTO dish_category VALUES (:dish, :category)")
                .bind("dish", id).bind("category", UUID.randomUUID()).fetch().rowsUpdated())
                .expectError(org.springframework.dao.DataIntegrityViolationException.class).verify();
    }
    @Test void failedRelationInsertRollsBackDishAndPreviousRelations() {
        UUID soups = category("Супы"), mains = category("Обеды");
        UUID id = dish("Борщ", "180.00", Set.of(soups));
        template.getDatabaseClient().sql("ALTER TABLE dish_category ADD CONSTRAINT ck_test_failure CHECK (false) NOT VALID").fetch().rowsUpdated().block();
        try {
            client.put().uri("/api/v1/dishes/" + id).bodyValue(payload("Изменено", "250.00", Set.of(mains)))
                    .exchange().expectStatus().isEqualTo(409);
        } finally {
            template.getDatabaseClient().sql("ALTER TABLE dish_category DROP CONSTRAINT ck_test_failure").fetch().rowsUpdated().block();
        }
        client.get().uri("/api/v1/dishes/" + id).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.name").isEqualTo("Борщ").jsonPath("$.version").isEqualTo(0)
                .jsonPath("$.categoryIds[0]").isEqualTo(soups.toString());
    }
    @Test void healthAndOpenApiDescribeOnlyPublicCatalog() {
        client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
        JsonNode document = client.get().uri("/v3/api-docs").exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(document.get("paths").toString()).doesNotContain("/internal/");
        assertThat(document.at("/paths/~1api~1v1~1dishes/post/responses/201/description").asText()).isEqualTo("Блюдо создано");
        client.get().uri("/swagger-ui/index.html").exchange().expectStatus().isOk();
    }
}
