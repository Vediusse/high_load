package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
class OrderApiIT extends AbstractPostgresIT {

    @Autowired
    WebTestClient client;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void draftCanBeEditedListedAndSubmittedWithCurrentPriceSnapshots() throws Exception {
        UUID organizationId = createOrganization("Альфа");
        UUID deliveryPointId = createDeliveryPoint(organizationId, "Главный офис");
        UUID categoryId = createCategory("Обеды");
        JsonNode soup = createDish("Борщ", "180.00", categoryId);
        JsonNode main = createDish("Котлета", "320.00", categoryId);
        OffsetDateTime deliveryAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);

        JsonNode draft = createOrder(organizationId, deliveryPointId, deliveryAt, "Первый заказ");
        UUID orderId = UUID.fromString(draft.get("id").asText());
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("lines").isEmpty()).isTrue();
        assertThat(draft.get("totalAmount").decimalValue()).isEqualByComparingTo("0.00");

        JsonNode details = body(client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/details").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "deliveryPointId", deliveryPointId,
                                "requestedDeliveryAt", deliveryAt.plusHours(1),
                                "comment", "Обновлённый комментарий",
                                "expectedVersion", draft.get("version").asLong()))).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.comment").isEqualTo("Обновлённый комментарий").returnResult());

        List<Map<String, Object>> lines = List.of(
                Map.of("dishId", UUID.fromString(soup.get("id").asText()), "quantity", 2),
                Map.of("dishId", UUID.fromString(main.get("id").asText()), "quantity", 3));
        JsonNode withLines = body(client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/lines").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", details.get("version").asLong(),
                                "lines", lines))).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.lines.length()").isEqualTo(2).returnResult());
        assertThat(withLines.get("totalAmount").decimalValue()).isEqualByComparingTo("1320.00");
        assertThat(withLines.get("lines")).allSatisfy(line -> {
            assertThat(line.get("unitPriceSnapshot").isNull()).isTrue();
            assertThat(line.get("lineAmount").isNull()).isTrue();
        });

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders").queryParam("page", "0").queryParam("size", "1").queryParam("status", "DRAFT").queryParam("organizationId", organizationId.toString()).buildAndExpand().toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectHeader().valueEquals("X-Total-Count", "1")
                .expectBody().jsonPath("$.items[0].id").isEqualTo(orderId.toString())
                .jsonPath("$.hasNext").isEqualTo(false);

        var changedSoup = catalog.get(UUID.fromString(soup.get("id").asText()));
        changedSoup.update("Борщ фирменный", "Описание", new BigDecimal("200.00"), Set.of());
        JsonNode repricedSoup = objectMapper.valueToTree(changedSoup);
        assertThat(repricedSoup.get("currentPrice").decimalValue()).isEqualByComparingTo("200.00");

        JsonNode submitted = body(client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/submit").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("expectedVersion", withLines.get("version").asLong()))).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.status").isEqualTo("SUBMITTED").returnResult());
        assertThat(submitted.get("totalAmount").decimalValue()).isEqualByComparingTo("1360.00");
        JsonNode soupLine = findLine(submitted, soup.get("id").asText());
        assertThat(soupLine.get("dishNameSnapshot").asText()).isEqualTo("Борщ фирменный");
        assertThat(soupLine.get("unitPriceSnapshot").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(soupLine.get("lineAmount").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                orderId)).isEqualTo(1L);

        client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/lines").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", submitted.get("version").asLong(),
                                "lines", lines))).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_STATUS_CONFLICT");
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/submit").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("expectedVersion", submitted.get("version").asLong()))).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_STATUS_CONFLICT");
        client.delete().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}").buildAndExpand(orderId).toUriString()).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_STATUS_CONFLICT");
    }

    @Test
    void orderBusinessValidationRejectsInvalidPartyLinesAndSubmit() throws Exception {
        UUID firstOrganization = createOrganization("Альфа");
        UUID secondOrganization = createOrganization("Бета");
        UUID foreignPoint = createDeliveryPoint(secondOrganization, "Чужой офис");
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);

        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders").buildAndExpand().toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(orderPayload(firstOrganization, foreignPoint, future, null))).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("DELIVERY_POINT_ORGANIZATION_MISMATCH");

        UUID point = createDeliveryPoint(firstOrganization, "Главный офис");
        UUID inactivePoint = createDeliveryPoint(firstOrganization, "Закрытый офис");
        client.delete().uri(UriComponentsBuilder.fromPath("/api/v1/delivery-points/{id}").buildAndExpand(inactivePoint).toUriString()).exchange()
                .expectStatus().isEqualTo(204);
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders").buildAndExpand().toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(orderPayload(firstOrganization, inactivePoint, future, null))).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("DELIVERY_POINT_INACTIVE");

        UUID inactiveOrganization = createOrganization("Закрытая организация");
        UUID pointOfInactiveOrganization = createDeliveryPoint(inactiveOrganization, "Старый офис");
        client.delete().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}").buildAndExpand(inactiveOrganization).toUriString()).exchange()
                .expectStatus().isEqualTo(204);
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders").buildAndExpand().toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(orderPayload(
                                inactiveOrganization,
                                pointOfInactiveOrganization,
                                future,
                                null))).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("ORGANIZATION_INACTIVE");

        UUID category = createCategory("Супы");
        JsonNode activeDish = createDish("Борщ", "180.00", category);
        JsonNode inactiveDish = createDish("Снятый суп", "150.00", category);
        catalog.get(UUID.fromString(inactiveDish.get("id").asText())).deactivate();

        JsonNode draft = createOrder(firstOrganization, point, future, null);
        UUID orderId = UUID.fromString(draft.get("id").asText());
        long version = draft.get("version").asLong();
        Map<String, Object> activeLine = Map.of("dishId", activeDish.get("id").asText(), "quantity", 1);

        client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/lines").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", version,
                                "lines", List.of(activeLine, activeLine)))).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("DUPLICATE_DISH");
        client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/lines").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", version,
                                "lines", List.of(Map.of(
                                        "dishId", activeDish.get("id").asText(),
                                        "quantity", 0))))).exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.fieldErrors[0].field").isEqualTo("lines[0].quantity");
        client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/lines").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", version,
                                "lines", List.of(Map.of(
                                        "dishId", inactiveDish.get("id").asText(),
                                        "quantity", 1))))).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("DISH_INACTIVE");

        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/submit").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("expectedVersion", version))).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_EMPTY");

        JsonNode pastDraft = createOrder(
                firstOrganization,
                point,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                null);
        UUID pastOrderId = UUID.fromString(pastDraft.get("id").asText());
        JsonNode pastWithLines = body(client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/lines").buildAndExpand(pastOrderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", pastDraft.get("version").asLong(),
                                "lines", List.of(activeLine)))).exchange()
                .expectStatus().isEqualTo(200).expectBody().returnResult());
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/submit").buildAndExpand(pastOrderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("expectedVersion", pastWithLines.get("version").asLong()))).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("DELIVERY_TIME_NOT_FUTURE");

        client.delete().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}").buildAndExpand(pastOrderId).toUriString()).exchange()
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_DELETE_FORBIDDEN");
        JsonNode emptyDraft = createOrder(firstOrganization, point, future, "Удалить");
        client.delete().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}").buildAndExpand(emptyDraft.get("id").asText()).toUriString()).exchange()
                .expectStatus().isEqualTo(204);
    }

    @Test
    void orderApiReturnsStableValidationNotFoundAndVersionErrors() throws Exception {
        UUID organizationId = createOrganization("Альфа");
        UUID pointId = createDeliveryPoint(organizationId, "Главный офис");
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
        JsonNode draft = createOrder(organizationId, pointId, future, null);
        UUID orderId = UUID.fromString(draft.get("id").asText());

        client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/details").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "deliveryPointId", pointId,
                                "requestedDeliveryAt", future,
                                "comment", "Новая версия",
                                "expectedVersion", 0))).exchange()
                .expectStatus().isEqualTo(200);
        client.put().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/details").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "deliveryPointId", pointId,
                                "requestedDeliveryAt", future,
                                "comment", "Устаревшая команда",
                                "expectedVersion", 0))).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_VERSION_CONFLICT");

        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders").buildAndExpand().toUriString())
                        .header("X-Trace-Id", "order-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}").exchange()
                .expectStatus().isEqualTo(400)
                .expectHeader().valueEquals("X-Trace-Id", "order-validation")
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.fieldErrors[*].field").value(hasItems(
                        "organizationId", "deliveryPointId", "requestedDeliveryAt"))
                .jsonPath("$.traceId").isEqualTo("order-validation");
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/submit").buildAndExpand(orderId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}").exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.fieldErrors[0].field").isEqualTo("expectedVersion");
        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders").queryParam("size", "51").buildAndExpand().toUriString()).exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.fieldErrors[0].field").isEqualTo("size");
        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders").buildAndExpand().toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectHeader().valueEquals("X-Total-Count", "1")
                .expectBody().jsonPath("$.items.length()").isEqualTo(1);
        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders").queryParam("status", "UNKNOWN").buildAndExpand().toUriString()).exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");
        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}").buildAndExpand(UUID.randomUUID()).toUriString()).exchange()
                .expectStatus().isEqualTo(404)
                .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    private UUID createOrganization(String name) throws Exception {
        EntityExchangeResult<byte[]> result = client.post().uri(UriComponentsBuilder.fromPath("/api/v1/organizations").buildAndExpand().toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("name", name, "phone", "+79991234567"))).exchange()
                .expectStatus().isEqualTo(201).expectBody().returnResult();
        return UUID.fromString(body(result).get("id").asText());
    }

    private UUID createDeliveryPoint(UUID organizationId, String name) throws Exception {
        EntityExchangeResult<byte[]> result = client.post().uri(UriComponentsBuilder.fromPath("/api/v1/organizations/{id}/delivery-points").buildAndExpand(organizationId).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "name", name,
                                "address", "Кронверкский проспект, 49",
                                "contactName", "Иван Петров",
                                "contactPhone", "+79991234568"))).exchange()
                .expectStatus().isEqualTo(201).expectBody().returnResult();
        return UUID.fromString(body(result).get("id").asText());
    }

    private UUID createCategory(String name) { return UUID.randomUUID(); }

    private JsonNode createDish(String name, String price, UUID categoryId) {
        return objectMapper.valueToTree(catalog.dish(name, price));
    }

    private JsonNode createOrder(
            UUID organizationId,
            UUID deliveryPointId,
            OffsetDateTime requestedDeliveryAt,
            String comment) throws Exception {
        EntityExchangeResult<byte[]> result = client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders").buildAndExpand().toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(orderPayload(
                                organizationId,
                                deliveryPointId,
                                requestedDeliveryAt,
                                comment))).exchange()
                .expectStatus().isEqualTo(201).expectBody().returnResult();
        return body(result);
    }

    private Map<String, Object> orderPayload(
            UUID organizationId,
            UUID deliveryPointId,
            OffsetDateTime requestedDeliveryAt,
            String comment) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("organizationId", organizationId);
        payload.put("deliveryPointId", deliveryPointId);
        payload.put("requestedDeliveryAt", requestedDeliveryAt);
        payload.put("comment", comment);
        return payload;
    }

    private JsonNode findLine(JsonNode order, String dishId) {
        for (JsonNode line : order.get("lines")) {
            if (line.get("dishId").asText().equals(dishId)) {
                return line;
            }
        }
        throw new AssertionError("Позиция блюда не найдена: " + dishId);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode body(EntityExchangeResult<byte[]> result) throws Exception {
        return objectMapper.readTree(new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
