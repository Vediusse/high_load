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
import ru.itmo.highload.catering.CatalogFixture.Dish;
import ru.itmo.highload.catering.order.dto.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.OrderLineInput;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.service.OrderService;
import ru.itmo.highload.catering.organization.entity.DeliveryPoint;
import ru.itmo.highload.catering.organization.entity.Organization;
import ru.itmo.highload.catering.organization.repository.DeliveryPointRepository;
import ru.itmo.highload.catering.organization.repository.OrganizationRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
class OrderLifecycleApiIT extends AbstractPostgresIT {

    @Autowired
    WebTestClient client;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OrderService orderService;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    DeliveryPointRepository deliveryPointRepository;


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

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/history").queryParam("page", "0").queryParam("size", "2").buildAndExpand(order.id()).toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.items.length()").isEqualTo(2)
                .jsonPath("$.items[0].fromStatus").isEqualTo("DRAFT")
                .jsonPath("$.items[0].toStatus").isEqualTo("SUBMITTED")
                .jsonPath("$.items[1].toStatus").isEqualTo("CONFIRMED")
                .jsonPath("$.items[0].orderId").isEqualTo(order.id().toString())
                .jsonPath("$.items[0].changedBy").isEqualTo((Object) null)
                .jsonPath("$.hasNext").isEqualTo(true);

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/history").queryParam("page", "2").queryParam("size", "2").buildAndExpand(order.id()).toUriString()).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.items.length()").isEqualTo(1)
                .jsonPath("$.items[0].fromStatus").isEqualTo("READY")
                .jsonPath("$.items[0].toStatus").isEqualTo("COMPLETED")
                .jsonPath("$.hasNext").isEqualTo(false);

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
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/reject").buildAndExpand(submitted.id()).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", submitted.version(),
                                "reason", " "))).exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.fieldErrors[0].field").isEqualTo("reason");
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/reject").buildAndExpand(submitted.id()).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", submitted.version(),
                                "reason", "x".repeat(501)))).exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.fieldErrors[0].field").isEqualTo("reason");
        assertOrderState(submitted.id(), "SUBMITTED", submitted.version(), 1);

        JsonNode rejected = body(client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/reject").buildAndExpand(submitted.id()).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", submitted.version(),
                                "reason", "  нет мощности кухни  "))).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.status").isEqualTo("REJECTED").returnResult());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM order_status_history WHERE order_id = ? AND to_status = 'REJECTED'",
                String.class,
                submitted.id())).isEqualTo("нет мощности кухни");
        assertStatusConflict(toOrderResponseVersion(rejected, submitted), "confirm");
        assertOrderState(submitted.id(), "REJECTED", rejected.get("version").asLong(), 2);

        OrderResponse stale = submittedOrder(fixture);
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/confirm").buildAndExpand(stale.id()).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("expectedVersion", stale.version() - 1))).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_VERSION_CONFLICT");
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

        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/history").buildAndExpand(UUID.randomUUID()).toUriString()).exchange()
                .expectStatus().isEqualTo(404)
                .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        client.get().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/history").queryParam("size", "51").buildAndExpand(stale.id()).toUriString()).exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.fieldErrors[0].field").isEqualTo("size");
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
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/cancel").buildAndExpand(cooking.id()).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", cooking.version(),
                                "reason", "слишком поздно"))).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_STATUS_CONFLICT");
        assertOrderState(cooking.id(), "IN_COOKING", cooking.version(), historyBefore);

        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/cancel").buildAndExpand(draftOrder(fixture).id()).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue("{}").exchange()
                .expectStatus().isEqualTo(400)
                .expectBody().jsonPath("$.fieldErrors[*].field").value(hasItems("expectedVersion", "reason"));
    }

    private OrderResponse command(OrderResponse order, String command, String expectedStatus) throws Exception {
        JsonNode response = body(client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/{command}").buildAndExpand(order.id(), command).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("expectedVersion", order.version()))).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.status").isEqualTo(expectedStatus).returnResult());
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
        client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/{command}").buildAndExpand(order.id(), command).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of("expectedVersion", order.version()))).exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_STATUS_CONFLICT");
        assertOrderState(order.id(), order.status().name(), order.version(), historyBefore);
    }

    private JsonNode cancel(OrderResponse order, String reason) throws Exception {
        return body(client.post().uri(UriComponentsBuilder.fromPath("/api/v1/orders/{id}/cancel").buildAndExpand(order.id()).toUriString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(json(Map.of(
                                "expectedVersion", order.version(),
                                "reason", reason))).exchange()
                .expectStatus().isEqualTo(200)
                .expectBody().jsonPath("$.status").isEqualTo("CANCELLED").returnResult());
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
        Dish dish = catalog.dish("Борщ", "180.00");
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

    private JsonNode body(EntityExchangeResult<byte[]> result) throws Exception {
        return objectMapper.readTree(new String(result.getResponseBody(), java.nio.charset.StandardCharsets.UTF_8));
    }

    private record Fixture(UUID organizationId, UUID deliveryPointId, UUID dishId) {
    }
}
