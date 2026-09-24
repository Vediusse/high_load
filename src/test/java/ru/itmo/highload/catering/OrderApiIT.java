package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class OrderApiIT extends AbstractPostgresIT {

    @Autowired
    MockMvc mockMvc;

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

        JsonNode details = body(mockMvc.perform(put("/api/v1/orders/{id}/details", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "deliveryPointId", deliveryPointId,
                                "requestedDeliveryAt", deliveryAt.plusHours(1),
                                "comment", "Обновлённый комментарий",
                                "expectedVersion", draft.get("version").asLong()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("Обновлённый комментарий"))
                .andReturn());

        List<Map<String, Object>> lines = List.of(
                Map.of("dishId", UUID.fromString(soup.get("id").asText()), "quantity", 2),
                Map.of("dishId", UUID.fromString(main.get("id").asText()), "quantity", 3));
        JsonNode withLines = body(mockMvc.perform(put("/api/v1/orders/{id}/lines", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", details.get("version").asLong(),
                                "lines", lines))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andReturn());
        assertThat(withLines.get("totalAmount").decimalValue()).isEqualByComparingTo("1320.00");
        assertThat(withLines.get("lines")).allSatisfy(line -> {
            assertThat(line.get("unitPriceSnapshot").isNull()).isTrue();
            assertThat(line.get("lineAmount").isNull()).isTrue();
        });

        mockMvc.perform(get("/api/v1/orders")
                        .param("page", "0")
                        .param("size", "1")
                        .param("status", "DRAFT")
                        .param("organizationId", organizationId.toString()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$.items[0].id").value(orderId.toString()))
                .andExpect(jsonPath("$.hasNext").value(false));

        JsonNode repricedSoup = body(mockMvc.perform(put("/api/v1/dishes/{id}", soup.get("id").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", "Борщ фирменный",
                                "description", "Описание",
                                "currentPrice", new BigDecimal("200.00"),
                                "categoryIds", Set.of(categoryId)))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(repricedSoup.get("currentPrice").decimalValue()).isEqualByComparingTo("200.00");

        JsonNode submitted = body(mockMvc.perform(post("/api/v1/orders/{id}/submit", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", withLines.get("version").asLong()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn());
        assertThat(submitted.get("totalAmount").decimalValue()).isEqualByComparingTo("1360.00");
        JsonNode soupLine = findLine(submitted, soup.get("id").asText());
        assertThat(soupLine.get("dishNameSnapshot").asText()).isEqualTo("Борщ фирменный");
        assertThat(soupLine.get("unitPriceSnapshot").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(soupLine.get("lineAmount").decimalValue()).isEqualByComparingTo("400.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                orderId)).isEqualTo(1L);

        mockMvc.perform(put("/api/v1/orders/{id}/lines", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", submitted.get("version").asLong(),
                                "lines", lines))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));
        mockMvc.perform(post("/api/v1/orders/{id}/submit", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", submitted.get("version").asLong()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));
        mockMvc.perform(delete("/api/v1/orders/{id}", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));
    }

    @Test
    void orderBusinessValidationRejectsInvalidPartyLinesAndSubmit() throws Exception {
        UUID firstOrganization = createOrganization("Альфа");
        UUID secondOrganization = createOrganization("Бета");
        UUID foreignPoint = createDeliveryPoint(secondOrganization, "Чужой офис");
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderPayload(firstOrganization, foreignPoint, future, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELIVERY_POINT_ORGANIZATION_MISMATCH"));

        UUID point = createDeliveryPoint(firstOrganization, "Главный офис");
        UUID inactivePoint = createDeliveryPoint(firstOrganization, "Закрытый офис");
        mockMvc.perform(delete("/api/v1/delivery-points/{id}", inactivePoint))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderPayload(firstOrganization, inactivePoint, future, null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELIVERY_POINT_INACTIVE"));

        UUID inactiveOrganization = createOrganization("Закрытая организация");
        UUID pointOfInactiveOrganization = createDeliveryPoint(inactiveOrganization, "Старый офис");
        mockMvc.perform(delete("/api/v1/organizations/{id}", inactiveOrganization))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderPayload(
                                inactiveOrganization,
                                pointOfInactiveOrganization,
                                future,
                                null))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORGANIZATION_INACTIVE"));

        UUID category = createCategory("Супы");
        JsonNode activeDish = createDish("Борщ", "180.00", category);
        JsonNode inactiveDish = createDish("Снятый суп", "150.00", category);
        mockMvc.perform(delete("/api/v1/dishes/{id}", inactiveDish.get("id").asText()))
                .andExpect(status().isNoContent());

        JsonNode draft = createOrder(firstOrganization, point, future, null);
        UUID orderId = UUID.fromString(draft.get("id").asText());
        long version = draft.get("version").asLong();
        Map<String, Object> activeLine = Map.of("dishId", activeDish.get("id").asText(), "quantity", 1);

        mockMvc.perform(put("/api/v1/orders/{id}/lines", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", version,
                                "lines", List.of(activeLine, activeLine)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_DISH"));
        mockMvc.perform(put("/api/v1/orders/{id}/lines", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", version,
                                "lines", List.of(Map.of(
                                        "dishId", activeDish.get("id").asText(),
                                        "quantity", 0))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lines[0].quantity"));
        mockMvc.perform(put("/api/v1/orders/{id}/lines", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", version,
                                "lines", List.of(Map.of(
                                        "dishId", inactiveDish.get("id").asText(),
                                        "quantity", 1))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DISH_INACTIVE"));

        mockMvc.perform(post("/api/v1/orders/{id}/submit", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", version))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_EMPTY"));

        JsonNode pastDraft = createOrder(
                firstOrganization,
                point,
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1),
                null);
        UUID pastOrderId = UUID.fromString(pastDraft.get("id").asText());
        JsonNode pastWithLines = body(mockMvc.perform(put("/api/v1/orders/{id}/lines", pastOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", pastDraft.get("version").asLong(),
                                "lines", List.of(activeLine)))))
                .andExpect(status().isOk())
                .andReturn());
        mockMvc.perform(post("/api/v1/orders/{id}/submit", pastOrderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", pastWithLines.get("version").asLong()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DELIVERY_TIME_NOT_FUTURE"));

        mockMvc.perform(delete("/api/v1/orders/{id}", pastOrderId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ORDER_DELETE_FORBIDDEN"));
        JsonNode emptyDraft = createOrder(firstOrganization, point, future, "Удалить");
        mockMvc.perform(delete("/api/v1/orders/{id}", emptyDraft.get("id").asText()))
                .andExpect(status().isNoContent());
    }

    @Test
    void orderApiReturnsStableValidationNotFoundAndVersionErrors() throws Exception {
        UUID organizationId = createOrganization("Альфа");
        UUID pointId = createDeliveryPoint(organizationId, "Главный офис");
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
        JsonNode draft = createOrder(organizationId, pointId, future, null);
        UUID orderId = UUID.fromString(draft.get("id").asText());

        mockMvc.perform(put("/api/v1/orders/{id}/details", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "deliveryPointId", pointId,
                                "requestedDeliveryAt", future,
                                "comment", "Новая версия",
                                "expectedVersion", 0))))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/orders/{id}/details", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "deliveryPointId", pointId,
                                "requestedDeliveryAt", future,
                                "comment", "Устаревшая команда",
                                "expectedVersion", 0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_VERSION_CONFLICT"));

        mockMvc.perform(post("/api/v1/orders")
                        .header("X-Trace-Id", "order-validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("X-Trace-Id", "order-validation"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems(
                        "organizationId", "deliveryPointId", "requestedDeliveryAt")))
                .andExpect(jsonPath("$.traceId").value("order-validation"));
        mockMvc.perform(post("/api/v1/orders/{id}/submit", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("expectedVersion"));
        mockMvc.perform(get("/api/v1/orders").param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "1"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/orders").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get("/api/v1/orders/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private UUID createOrganization(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "phone", "+79991234567"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    private UUID createDeliveryPoint(UUID organizationId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/organizations/{id}/delivery-points", organizationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", name,
                                "address", "Кронверкский проспект, 49",
                                "contactName", "Иван Петров",
                                "contactPhone", "+79991234568"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    private UUID createCategory(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(body(result).get("id").asText());
    }

    private JsonNode createDish(String name, String price, UUID categoryId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/dishes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "name", name,
                                "description", "Описание",
                                "currentPrice", new BigDecimal(price),
                                "categoryIds", Set.of(categoryId)))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    private JsonNode createOrder(
            UUID organizationId,
            UUID deliveryPointId,
            OffsetDateTime requestedDeliveryAt,
            String comment) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(orderPayload(
                                organizationId,
                                deliveryPointId,
                                requestedDeliveryAt,
                                comment))))
                .andExpect(status().isCreated())
                .andReturn();
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

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
