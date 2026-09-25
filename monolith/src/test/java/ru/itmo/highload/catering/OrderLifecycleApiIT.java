package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import ru.itmo.highload.catering.catalog.entity.Dish;
import ru.itmo.highload.catering.catalog.repository.DishRepository;
import ru.itmo.highload.catering.order.dto.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.OrderLineInput;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.service.OrderService;
import ru.itmo.highload.catering.organization.entity.DeliveryPoint;
import ru.itmo.highload.catering.organization.entity.Organization;
import ru.itmo.highload.catering.organization.repository.DeliveryPointRepository;
import ru.itmo.highload.catering.organization.repository.OrganizationRepository;

@SpringBootTest
@AutoConfigureMockMvc
class OrderLifecycleApiIT extends AbstractPostgresIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderService orderService;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    DeliveryPointRepository deliveryPointRepository;

    @Autowired
    DishRepository dishRepository;

    @Test
    void fullLifecycleReachesCompletedAndHistoryIsChronologicalAndPaged() throws Exception {
        Fixture fixture = fixture();
        OrderResponse order = submittedOrder(fixture);

        order = command(order, "confirm", "CONFIRMED");
        order = command(order, "start-cooking", "IN_COOKING");
        order = command(order, "mark-ready", "READY");
        order = command(order, "complete", "COMPLETED");

        assertThat(order.totalAmount()).isEqualByComparingTo("360.00");
        assertThat(order.lines()).singleElement().satisfies(line -> {
            assertThat(line.dishNameSnapshot()).isEqualTo("Борщ");
            assertThat(line.unitPriceSnapshot()).isEqualByComparingTo("180.00");
        });

        mockMvc.perform(get("/api/v1/orders/{id}/history", order.id())
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].fromStatus").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].toStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.items[1].toStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.items[0].orderId").value(order.id().toString()))
                .andExpect(jsonPath("$.items[0].changedBy").value((Object) null))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(get("/api/v1/orders/{id}/history", order.id())
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].fromStatus").value("READY"))
                .andExpect(jsonPath("$.items[0].toStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.hasNext").value(false));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                order.id())).isEqualTo(5L);
    }

    @Test
    void rejectAndEverySkippedTransitionReturnStableErrorsWithoutMutation() throws Exception {
        Fixture fixture = fixture();
        OrderResponse draft = draftOrder(fixture);
        assertStatusConflict(draft, "confirm");

        OrderResponse submitted = submittedOrder(fixture);
        mockMvc.perform(post("/api/v1/orders/{id}/reject", submitted.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", submitted.version(),
                                "reason", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        mockMvc.perform(post("/api/v1/orders/{id}/reject", submitted.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", submitted.version(),
                                "reason", "x".repeat(501)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        assertOrderState(submitted.id(), "SUBMITTED", submitted.version(), 1);

        JsonNode rejected = body(mockMvc.perform(post("/api/v1/orders/{id}/reject", submitted.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", submitted.version(),
                                "reason", "  нет мощности кухни  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andReturn());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM order_status_history WHERE order_id = ? AND to_status = 'REJECTED'",
                String.class,
                submitted.id())).isEqualTo("нет мощности кухни");
        assertStatusConflict(toOrderResponseVersion(rejected, submitted), "confirm");
        assertOrderState(submitted.id(), "REJECTED", rejected.get("version").asLong(), 2);

        OrderResponse stale = submittedOrder(fixture);
        mockMvc.perform(post("/api/v1/orders/{id}/confirm", stale.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", stale.version() - 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_VERSION_CONFLICT"));
        assertOrderState(stale.id(), "SUBMITTED", stale.version(), 1);

        OrderResponse notConfirmed = submittedOrder(fixture);
        assertStatusConflict(notConfirmed, "start-cooking");

        OrderResponse awaitingConfirmation = submittedOrder(fixture);
        OrderResponse confirmed = orderService.confirm(
                awaitingConfirmation.id(),
                awaitingConfirmation.version());
        assertStatusConflict(confirmed, "mark-ready");

        OrderResponse anotherSubmitted = submittedOrder(fixture);
        OrderResponse anotherConfirmed = orderService.confirm(
                anotherSubmitted.id(),
                anotherSubmitted.version());
        OrderResponse cooking = orderService.startCooking(
                anotherConfirmed.id(),
                anotherConfirmed.version());
        assertStatusConflict(cooking, "complete");

        mockMvc.perform(get("/api/v1/orders/{id}/history", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/orders/{id}/history", stale.id()).param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("size"));
    }

    @Test
    void cancellationWorksFromDraftSubmittedAndConfirmedButNotAfterCooking() throws Exception {
        Fixture fixture = fixture();

        OrderResponse draft = draftOrder(fixture);
        JsonNode cancelledDraft = cancel(draft, "черновик больше не нужен");
        assertThat(cancelledDraft.get("status").asText()).isEqualTo("CANCELLED");

        OrderResponse submitted = submittedOrder(fixture);
        JsonNode cancelledSubmitted = cancel(submitted, "заказ перенесён");
        assertThat(cancelledSubmitted.get("status").asText()).isEqualTo("CANCELLED");

        OrderResponse awaitingConfirmation = submittedOrder(fixture);
        OrderResponse confirmed = orderService.confirm(
                awaitingConfirmation.id(),
                awaitingConfirmation.version());
        JsonNode cancelledConfirmed = cancel(confirmed, "офис закрыт");
        assertThat(cancelledConfirmed.get("status").asText()).isEqualTo("CANCELLED");

        OrderResponse anotherSubmitted = submittedOrder(fixture);
        OrderResponse anotherConfirmed = orderService.confirm(
                anotherSubmitted.id(),
                anotherSubmitted.version());
        OrderResponse cooking = orderService.startCooking(
                anotherConfirmed.id(),
                anotherConfirmed.version());
        int historyBefore = historyCount(cooking.id());
        mockMvc.perform(post("/api/v1/orders/{id}/cancel", cooking.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", cooking.version(),
                                "reason", "слишком поздно"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));
        assertOrderState(cooking.id(), "IN_COOKING", cooking.version(), historyBefore);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", draftOrder(fixture).id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItems("expectedVersion", "reason")));
    }

    private OrderResponse command(OrderResponse order, String command, String expectedStatus) throws Exception {
        JsonNode response = body(mockMvc.perform(post("/api/v1/orders/{id}/{command}", order.id(), command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", order.version()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andReturn());
        return new OrderResponse(
                order.id(),
                order.organizationId(),
                order.deliveryPointId(),
                order.requestedDeliveryAt(),
                ru.itmo.highload.catering.order.entity.OrderStatus.valueOf(response.get("status").asText()),
                response.get("totalAmount").decimalValue(),
                order.comment(),
                response.get("version").asLong(),
                order.createdAt(),
                order.lines());
    }

    private void assertStatusConflict(OrderResponse order, String command) throws Exception {
        int historyBefore = historyCount(order.id());
        mockMvc.perform(post("/api/v1/orders/{id}/{command}", order.id(), command)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("expectedVersion", order.version()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATUS_CONFLICT"));
        assertOrderState(order.id(), order.status().name(), order.version(), historyBefore);
    }

    private JsonNode cancel(OrderResponse order, String reason) throws Exception {
        return body(mockMvc.perform(post("/api/v1/orders/{id}/cancel", order.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "expectedVersion", order.version(),
                                "reason", reason))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andReturn());
    }

    private void assertOrderState(UUID orderId, String status, long version, int historyCount) {
        Map<String, Object> state = jdbcTemplate.queryForMap(
                "SELECT status, version FROM corporate_order WHERE id = ?",
                orderId);
        assertThat(state.get("status")).isEqualTo(status);
        assertThat(((Number) state.get("version")).longValue()).isEqualTo(version);
        assertThat(historyCount(orderId)).isEqualTo(historyCount);
    }

    private int historyCount(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Integer.class,
                orderId);
    }

    private OrderResponse toOrderResponseVersion(JsonNode response, OrderResponse original) {
        return new OrderResponse(
                original.id(),
                original.organizationId(),
                original.deliveryPointId(),
                original.requestedDeliveryAt(),
                ru.itmo.highload.catering.order.entity.OrderStatus.valueOf(response.get("status").asText()),
                response.get("totalAmount").decimalValue(),
                original.comment(),
                response.get("version").asLong(),
                original.createdAt(),
                original.lines());
    }

    private Fixture fixture() {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Альфа", "+79991234567"));
        DeliveryPoint point = deliveryPointRepository.saveAndFlush(new DeliveryPoint(
                organization,
                "Главный офис",
                "Кронверкский проспект, 49",
                "Иван Петров",
                "+79991234568"));
        Dish dish = dishRepository.saveAndFlush(
                new Dish("Борщ", "Описание", new BigDecimal("180.00"), Set.of()));
        return new Fixture(organization.getId(), point.getId(), dish.getId());
    }

    private OrderResponse draftOrder(Fixture fixture) {
        return orderService.createDraft(new CreateOrderRequest(
                fixture.organizationId(),
                fixture.deliveryPointId(),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2),
                null));
    }

    private OrderResponse submittedOrder(Fixture fixture) {
        OrderResponse draft = draftOrder(fixture);
        OrderResponse withLines = orderService.replaceDraftLines(
                draft.id(),
                new ReplaceOrderLinesRequest(
                        draft.version(),
                        List.of(new OrderLineInput(fixture.dishId(), 2))));
        return orderService.submit(draft.id(), withLines.version());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record Fixture(UUID organizationId, UUID deliveryPointId, UUID dishId) {
    }
}
