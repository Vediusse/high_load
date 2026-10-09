package ru.itmo.highload.catering;

import static org.hamcrest.Matchers.hasItems;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
class OrganizationApiIT extends AbstractPostgresIT {

    @Autowired
    WebTestClient client;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void organizationAndPointPagesHaveStableOrderAfterUpdates() throws Exception {
        var organizations = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            organizations.add(createOrganization("Компания " + i, "+79991234567").get("id").asText());
        }
        organizations.sort(String::compareTo);
        String organization = organizations.getFirst();
        client.put().uri("/api/v1/organizations/" + organization)
                .bodyValue(Map.of("name", "Новое название", "phone", "+79991234567"))
                .exchange().expectStatus().isOk();
        var points = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            var point = client.post().uri("/api/v1/organizations/" + organization + "/delivery-points")
                    .bodyValue(pointPayload("Офис " + i)).exchange().expectStatus().isCreated()
                    .expectBody(JsonNode.class).returnResult().getResponseBody();
            points.add(point.get("id").asText());
        }
        points.sort(String::compareTo);
        client.put().uri("/api/v1/delivery-points/" + points.getFirst()).bodyValue(pointPayload("Новый офис"))
                .exchange().expectStatus().isOk();
        for (int page = 0; page < 3; page++) {
            client.get().uri("/api/v1/organizations?size=1&page=" + page).exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.items[0].id").isEqualTo(organizations.get(page));
            client.get().uri("/api/v1/organizations/" + organization + "/delivery-points?size=1&page=" + page)
                    .exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].id").isEqualTo(points.get(page));
        }
    }

    @Test
    void organizationAndDeliveryPointCrudUsesDtosAndPagination() throws Exception {
        JsonNode organization = createOrganization("Альфа", "+79991234567");
        UUID organizationId = UUID.fromString(organization.get("id").asText());

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}").buildAndExpand(organizationId).toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.name").isEqualTo("Альфа")
                .jsonPath("$.active").isEqualTo(true)
                .jsonPath("$.deliveryPoints").doesNotExist();

        client.put().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}").buildAndExpand(organizationId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("name", "Альфа Плюс", "phone", "+79990000000"))).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.name").isEqualTo("Альфа Плюс")
                .jsonPath("$.version").isEqualTo(1);

        createOrganization("Бета", "+79991234568");
        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/organizations").queryParam("page", "0").queryParam("size", "1").buildAndExpand().toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectHeader().valueEquals("X-Total-Count", "2")
                .expectBody().jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.page").isEqualTo(0)
                .jsonPath("$.size").isEqualTo(1)
                .jsonPath("$.hasNext").isEqualTo(true)
                .jsonPath("$.content").doesNotExist();

        EntityExchangeResult<byte[]> pointResult = client.post().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}/delivery-points").buildAndExpand(organizationId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(pointPayload("Главный офис"))).exchange()
                .expectStatus().isEqualTo(201)
                .expectHeader().value("Location", org.hamcrest.Matchers.containsString("/delivery-points/"))
                .expectBody().jsonPath("$.organizationId").isEqualTo(organizationId.toString())
                .jsonPath("$.active").isEqualTo(true).returnResult();
        UUID pointId = UUID.fromString(body(pointResult).get("id").asText());

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}/delivery-points").queryParam("page", "0").queryParam("size", "20").buildAndExpand(organizationId).toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.items[0].id").isEqualTo(pointId.toString())
                .jsonPath("$.hasNext").isEqualTo(false);

        client.put().uri(UriComponentsBuilder.fromPath("/api/v1/delivery-points/{id}").buildAndExpand(pointId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "name", "Новый офис",
                                "address", "Невский проспект, 2",
                                "contactName", "Мария",
                                "contactPhone", "+79991111111"))).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.name").isEqualTo("Новый офис");

        client.delete().uri(UriComponentsBuilder.fromPath("/api/v1/delivery-points/{id}").buildAndExpand(pointId).toUriString()).exchange()
                .expectStatus().isEqualTo(204);
        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}/delivery-points").buildAndExpand(organizationId).toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.items[0].active").isEqualTo(false);

        client.delete().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}").buildAndExpand(organizationId).toUriString()).exchange()
                .expectStatus().isEqualTo(204);
        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}").buildAndExpand(organizationId).toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.active").isEqualTo(false);
    }

    @Test
    void returnsStableValidationAndNotFoundErrorsWithTraceId() throws Exception {
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/organizations").buildAndExpand().toUriString())
                        .header("X-Trace-Id", "organization-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("name", " ", "phone", "8999"))).exchange()
                .expectStatus().isEqualTo(400)
                .expectHeader().valueEquals("X-Trace-Id", "organization-validation")
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.message").isNotEmpty()
                .jsonPath("$.traceId").isEqualTo("organization-validation")
                .jsonPath("$.fieldErrors[*].field").value(hasItems("name", "phone"));

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/organizations").queryParam("size", "51").buildAndExpand().toUriString()).exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.fieldErrors[0].field").isEqualTo("size");

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}/delivery-points").queryParam("size", "51").buildAndExpand(UUID.randomUUID()).toUriString())
                        .exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.fieldErrors[0].field").isEqualTo("size");

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}").buildAndExpand(UUID.randomUUID()).toUriString()).exchange()
                .expectStatus().isEqualTo(404)
                .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
                .jsonPath("$.traceId").isNotEmpty();
    }

    @Test
    void inactiveOrganizationAndDuplicatePointNameAreRejected() throws Exception {
        UUID organizationId = UUID.fromString(createOrganization("Альфа", "+79991234567").get("id").asText());

        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}/delivery-points").buildAndExpand(organizationId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(pointPayload("Офис"))).exchange()
                .expectStatus().isEqualTo(201);
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}/delivery-points").buildAndExpand(organizationId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(pointPayload("Офис"))).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("DELIVERY_POINT_NAME_CONFLICT");

        client.delete().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}").buildAndExpand(organizationId).toUriString()).exchange()
                .expectStatus().isEqualTo(204);
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}/delivery-points").buildAndExpand(organizationId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(pointPayload("Запасной офис"))).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("ORGANIZATION_INACTIVE");
    }

    private JsonNode createOrganization(String name, String phone) throws Exception {
        EntityExchangeResult<byte[]> result = client.post().uri(UriComponentsBuilder.fromPath("/api/v1/organizations").buildAndExpand().toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("name", name, "phone", phone))).exchange()
                .expectStatus().isEqualTo(201)
                .expectHeader().value("Location", org.hamcrest.Matchers.containsString("/organizations/"))
                .expectBody().jsonPath("$.active").isEqualTo(true).returnResult();
        return body(result);
    }

    private Map<String, String> pointPayload(String name) {
        return Map.of(
                "name", name,
                "address", "Невский проспект, 1",
                "contactName", "Анна",
                "contactPhone", "+79997654321");
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode body(EntityExchangeResult<byte[]> result) throws Exception {
        return objectMapper.readTree(new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
