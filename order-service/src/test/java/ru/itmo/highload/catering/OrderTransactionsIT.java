package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import ru.itmo.highload.catering.CatalogFixture.Dish;
import ru.itmo.highload.catering.order.dto.in.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.in.OrderLineInput;
import ru.itmo.highload.catering.order.dto.in.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.dto.out.OrderResponse;
import ru.itmo.highload.catering.order.service.OrderService;
import ru.itmo.highload.catering.organization.entity.DeliveryPoint;
import ru.itmo.highload.catering.organization.entity.Organization;
import ru.itmo.highload.catering.organization.repository.DeliveryPointRepository;
import ru.itmo.highload.catering.organization.repository.OrganizationRepository;
import ru.itmo.highload.common.error.ApiException;

@SpringBootTest
class OrderTransactionsIT extends AbstractPostgresIT {

    @Autowired
    OrderService orderService;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    DeliveryPointRepository deliveryPointRepository;

    @Autowired
    ru.itmo.highload.catering.catalog.service.CatalogGateway catalogGateway;

    @Test
    void largeCatalogReadUsesBoundedBatchesAndFailureCannotPartiallyReplaceOrder() {
        var ids = new java.util.LinkedHashSet<UUID>();
        for (int i = 0; i < 1001; i++) ids.add(dish("Блюдо " + i, "100.00").getId());
        assertThat(catalogGateway.getActiveDishPrices(ids, ru.itmo.highload.common.security.TestTokens.bearer()).keySet()).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(catalog.batchSizes).containsExactly(1000, 1);

        var draft = createDraft(fixture());
        var missingLast = new java.util.ArrayList<>(ids.stream().limit(1000)
                .map(id -> new OrderLineInput(id, 1)).toList());
        missingLast.add(new OrderLineInput(UUID.randomUUID(), 1));
        catalog.batchSizes.clear();
        assertThatThrownBy(() -> orderService.replaceDraftLines(draft.id(),
                new ReplaceOrderLinesRequest(draft.version(), missingLast), ru.itmo.highload.common.security.TestTokens.bearer()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.getCode()).isEqualTo("RESOURCE_NOT_FOUND"));
        assertThat(catalog.batchSizes).containsExactly(1000, 1);
        assertThat(orderService.getOrder(draft.id())).isEqualTo(draft);
    }


    @Test
    void failedLineReplacementKeepsPreviousCompositionCompletely() {
        Fixture fixture = fixture();
        Dish oldDish = dish("Старое блюдо", "100.00");
        Dish newDish = dish("Новое блюдо", "200.00");
        OrderResponse draft = createDraft(fixture);
        OrderResponse initial = orderService.replaceDraftLines(
                draft.id(),
                new ReplaceOrderLinesRequest(
                        draft.version(),
                        List.of(new OrderLineInput(oldDish.getId(), 2))), ru.itmo.highload.common.security.TestTokens.bearer());

        UUID missingDish = UUID.randomUUID();
        assertThatThrownBy(() -> orderService.replaceDraftLines(
                        draft.id(),
                        new ReplaceOrderLinesRequest(
                                initial.version(),
                                List.of(
                                        new OrderLineInput(newDish.getId(), 1),
                                        new OrderLineInput(missingDish, 1))), ru.itmo.highload.common.security.TestTokens.bearer()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("RESOURCE_NOT_FOUND"));

        OrderResponse unchanged = orderService.getOrder(draft.id());
        assertThat(unchanged.lines()).singleElement().satisfies(line -> {
            assertThat(line.dishId()).isEqualTo(oldDish.getId());
            assertThat(line.quantity()).isEqualTo(2);
        });
        assertThat(unchanged.totalAmount()).isEqualByComparingTo("200.00");
        assertThat(unchanged.version()).isEqualTo(initial.version());
    }

    @Test
    void failedSubmitPersistsNoSnapshotsStatusOrHistory() {
        Fixture fixture = fixture();
        Dish activeDish = dish("Активное блюдо", "100.00");
        Dish laterInactiveDish = dish("Будет снято", "200.00");
        OrderResponse draft = createDraft(fixture);
        OrderResponse withLines = orderService.replaceDraftLines(
                draft.id(),
                new ReplaceOrderLinesRequest(
                        draft.version(),
                        List.of(
                                new OrderLineInput(activeDish.getId(), 1),
                                new OrderLineInput(laterInactiveDish.getId(), 2))), ru.itmo.highload.common.security.TestTokens.bearer());
        laterInactiveDish.deactivate();

        assertThatThrownBy(() -> orderService.submit(draft.id(), withLines.version(), ru.itmo.highload.common.security.TestTokens.bearer()))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("DISH_INACTIVE"));

        OrderResponse unchanged = orderService.getOrder(draft.id());
        assertThat(unchanged.status().name()).isEqualTo("DRAFT");
        assertThat(unchanged.totalAmount()).isEqualByComparingTo("500.00");
        assertThat(unchanged.lines()).allSatisfy(line -> {
            assertThat(line.dishNameSnapshot()).isNull();
            assertThat(line.unitPriceSnapshot()).isNull();
            assertThat(line.lineAmount()).isNull();
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                draft.id())).isZero();
    }

    @Test
    void submittedPriceSnapshotDoesNotChangeAfterDishUpdate() {
        Fixture fixture = fixture();
        Dish dish = dish("Борщ", "180.00");
        OrderResponse draft = createDraft(fixture);
        OrderResponse withLines = orderService.replaceDraftLines(
                draft.id(),
                new ReplaceOrderLinesRequest(
                        draft.version(),
                        List.of(new OrderLineInput(dish.getId(), 2))), ru.itmo.highload.common.security.TestTokens.bearer());

        dish.update("Борщ фирменный", "", new BigDecimal("200.00"), Set.of());
        OrderResponse submitted = orderService.submit(draft.id(), withLines.version(), ru.itmo.highload.common.security.TestTokens.bearer());
        dish.update("Борщ новый", "", new BigDecimal("300.00"), Set.of());

        OrderResponse unchanged = orderService.getOrder(draft.id());
        assertThat(unchanged.totalAmount()).isEqualByComparingTo("400.00");
        assertThat(unchanged.lines()).singleElement().satisfies(line -> {
            assertThat(line.dishNameSnapshot()).isEqualTo("Борщ фирменный");
            assertThat(line.unitPriceSnapshot()).isEqualByComparingTo("200.00");
            assertThat(line.lineAmount()).isEqualByComparingTo("400.00");
        });
        assertThat(unchanged.version()).isEqualTo(submitted.version());
    }

    @Test
    void failedHistoryInsertRollsBackStatusAndVersion() {
        Fixture fixture = fixture();
        Dish dish = dish("Борщ", "180.00");
        OrderResponse draft = createDraft(fixture);
        OrderResponse withLines = orderService.replaceDraftLines(
                draft.id(),
                new ReplaceOrderLinesRequest(
                        draft.version(),
                        List.of(new OrderLineInput(dish.getId(), 2))), ru.itmo.highload.common.security.TestTokens.bearer());
        OrderResponse submitted = orderService.submit(draft.id(), withLines.version(), ru.itmo.highload.common.security.TestTokens.bearer());

        jdbcTemplate.execute("""
                ALTER TABLE order_status_history
                ADD CONSTRAINT ck_test_reject_new_history CHECK (false) NOT VALID
                """);
        try {
            assertThatThrownBy(() -> orderService.confirm(submitted.id(), submitted.version()))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE order_status_history
                    DROP CONSTRAINT ck_test_reject_new_history
                    """);
        }

        OrderResponse unchanged = orderService.getOrder(submitted.id());
        assertThat(unchanged.status().name()).isEqualTo("SUBMITTED");
        assertThat(unchanged.version()).isEqualTo(submitted.version());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                submitted.id())).isEqualTo(1L);
    }

    @Test
    void unavailableCatalogCannotChangeCompositionOrSubmitAndCircuitRecovers() {
        Fixture fixture = fixture();
        Dish dish = dish("Борщ", "180.00");
        OrderResponse draft = createDraft(fixture);
        OrderResponse filled = orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                draft.version(), List.of(new OrderLineInput(dish.getId(), 2))), ru.itmo.highload.common.security.TestTokens.bearer());
        var breaker = breakers.circuitBreaker("catalog");
        breaker.reset();
        catalog.failureStatus = 503;
        for (int attempt = 0; attempt < breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls(); attempt++) {
            assertThatThrownBy(() -> orderService.submit(filled.id(), filled.version(), ru.itmo.highload.common.security.TestTokens.bearer()))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.getStatus().value()).isEqualTo(503);
                        assertThat(error.getCode()).isEqualTo("DEPENDENCY_UNAVAILABLE");
                    });
        }
        assertThat(breakers.circuitBreaker("catalog").getState().name()).isEqualTo("OPEN");
        int sent = catalog.requests.get();
        assertThatThrownBy(() -> orderService.replaceDraftLines(filled.id(), new ReplaceOrderLinesRequest(
                filled.version(), List.of(new OrderLineInput(dish.getId(), 3))), ru.itmo.highload.common.security.TestTokens.bearer())).isInstanceOf(ApiException.class);
        assertThat(catalog.requests.get()).isEqualTo(sent);
        OrderResponse unchanged = orderService.getOrder(filled.id());
        assertThat(unchanged).isEqualTo(filled);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_status_history", Long.class)).isZero();
        catalog.failureStatus = 0;
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(8)).ignoreException(ApiException.class).untilAsserted(() -> {
            assertThat(orderService.submit(filled.id(), filled.version(), ru.itmo.highload.common.security.TestTokens.bearer()).status().name()).isEqualTo("SUBMITTED");
        });
        // Complete the remaining successful probes in the half-open window.
        for (int probe = 1; probe < breaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState(); probe++) {
            var another = createDraft(fixture);
            orderService.replaceDraftLines(another.id(), new ReplaceOrderLinesRequest(another.version(),
                    List.of(new OrderLineInput(dish.getId(), 1))), ru.itmo.highload.common.security.TestTokens.bearer());
        }
        assertThat(breakers.circuitBreaker("catalog").getState().name()).isEqualTo("CLOSED");
    }

    @Test
    void failedHalfOpenProbesReopenCatalogCircuit() {
        var ids = Set.of(dish("Суп", "100.00").getId());
        var breaker = breakers.circuitBreaker("catalog");
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        catalog.failureStatus = 503;
        int probes = breaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState();
        for (int i = 0; i < probes; i++) {
            assertThatThrownBy(() -> catalogGateway.getActiveDishPrices(ids, ru.itmo.highload.common.security.TestTokens.bearer())).isInstanceOf(ApiException.class);
        }
        assertThat(breaker.getState().name()).isEqualTo("OPEN");
        assertThat(catalog.requests.get()).isEqualTo(probes);
        assertThatThrownBy(() -> catalogGateway.getActiveDishPrices(ids, ru.itmo.highload.common.security.TestTokens.bearer())).isInstanceOf(ApiException.class);
        assertThat(catalog.requests.get()).isEqualTo(probes);
    }

    @Test
    void halfOpenWithoutEnoughProbesHasBoundedLifetime() {
        var breaker = breakers.circuitBreaker("catalog");
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        org.awaitility.Awaitility.await()
                .atMost(breaker.getCircuitBreakerConfig().getMaxWaitDurationInHalfOpenState()
                        .plusSeconds(2))
                .untilAsserted(() -> assertThat(breaker.getState().name()).isEqualTo("OPEN"));
        assertThat(catalog.requests.get()).isZero();
    }

    @Test
    void slowSuccessfulCallsOpenCircuitOnlyAfterMinimumSample() {
        var breaker = breakers.circuitBreaker("catalog");
        var config = breaker.getCircuitBreakerConfig();
        long slowMillis = config.getSlowCallDurationThreshold().toMillis() + 1;
        for (int i = 1; i < config.getMinimumNumberOfCalls(); i++) {
            breaker.onSuccess(slowMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        assertThat(breaker.getState().name()).isEqualTo("CLOSED");
        breaker.onSuccess(slowMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(breaker.getState().name()).isEqualTo("OPEN");
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(breaker.getMetrics().getSlowCallRate()).isEqualTo(100);
    }

    @Test
    void timeoutAndIncompleteBatchLeaveDraftUntouched() {
        Fixture fixture = fixture();
        Dish dish = dish("Борщ", "180.00");
        OrderResponse draft = createDraft(fixture);
        var command = new ReplaceOrderLinesRequest(draft.version(), List.of(new OrderLineInput(dish.getId(), 1)));
        catalog.delayMillis = 3000;
        long start = System.nanoTime();
        assertThatThrownBy(() -> orderService.replaceDraftLines(draft.id(), command, ru.itmo.highload.common.security.TestTokens.bearer()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(503));
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start).toMillis()).isBetween(1800L, 5000L);
        catalog.delayMillis = 0;
        catalog.incomplete = true;
        assertThatThrownBy(() -> orderService.replaceDraftLines(draft.id(), command, ru.itmo.highload.common.security.TestTokens.bearer()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(503));
        assertThat(orderService.getOrder(draft.id())).isEqualTo(draft);
        assertThat(catalog.requests.get()).isEqualTo(2);
    }

    @Test
    void businessErrorsDoNotOpenCircuitAndTraceReachesCatalog() {
        Fixture fixture = fixture();
        OrderResponse draft = createDraft(fixture);
        for (int attempt = 0; attempt < breakers.circuitBreaker("catalog").getCircuitBreakerConfig().getMinimumNumberOfCalls() + 1; attempt++) {
            assertThatThrownBy(() -> orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                    draft.version(), List.of(new OrderLineInput(UUID.randomUUID(), 1))), ru.itmo.highload.common.security.TestTokens.bearer()))
                    .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getStatus().value()).isEqualTo(404));
        }
        assertThat(breakers.circuitBreaker("catalog").getState().name()).isEqualTo("CLOSED");
        assertThat(breakers.circuitBreaker("catalog").getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(catalog.requests.get()).isEqualTo(
                breakers.circuitBreaker("catalog").getCircuitBreakerConfig().getMinimumNumberOfCalls() + 1);
        org.slf4j.MDC.put("traceId", "order-catalog-trace");
        try {
            Dish dish = dish("Борщ", "180.00");
            orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(draft.version(),
                    List.of(new OrderLineInput(dish.getId(), 1))), ru.itmo.highload.common.security.TestTokens.bearer());
            assertThat(catalog.lastTrace).isEqualTo("order-catalog-trace");
        } finally {
            org.slf4j.MDC.remove("traceId");
        }
    }

    @Test
    void downstreamSecurityFailurePreservesStatusAndCannotChangeDraft() {
        OrderResponse draft = createDraft(fixture());
        String bearer = ru.itmo.highload.common.security.TestTokens.bearer();
        var request = new ReplaceOrderLinesRequest(draft.version(),
                List.of(new OrderLineInput(UUID.randomUUID(), 1)));
        for (int status : List.of(401, 403)) {
            catalog.failureStatus = status;
            assertThatThrownBy(() -> orderService.replaceDraftLines(draft.id(), request, bearer))
                    .isInstanceOfSatisfying(ApiException.class, error ->
                            assertThat(error.getStatus().value()).isEqualTo(status));
            assertThat(catalog.lastBearer).isEqualTo(bearer);
            assertThat(orderService.getOrder(draft.id())).isEqualTo(draft);
        }
        assertThat(breakers.circuitBreaker("catalog").getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void retainedLinesCanChangeQuantityAndVersionEvenWhenTotalStaysTheSame() {
        OrderResponse draft = createDraft(fixture());
        Dish first = dish("Суп", "100.00");
        Dish second = dish("Горячее", "200.00");
        Dish third = dish("Салат", "100.00");
        OrderResponse initial = orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                draft.version(), List.of(new OrderLineInput(first.getId(), 1))), ru.itmo.highload.common.security.TestTokens.bearer());
        UUID lineId = initial.lines().getFirst().id();

        OrderResponse increased = orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                initial.version(), List.of(new OrderLineInput(first.getId(), 5))), ru.itmo.highload.common.security.TestTokens.bearer());
        assertThat(increased.totalAmount()).isEqualByComparingTo("500.00");
        assertThat(increased.version()).isEqualTo(initial.version() + 1);
        assertThat(increased.lines()).singleElement().satisfies(line -> {
            assertThat(line.id()).isEqualTo(lineId);
            assertThat(line.quantity()).isEqualTo(5);
        });

        OrderResponse sameTotal = orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                increased.version(), List.of(new OrderLineInput(first.getId(), 3),
                        new OrderLineInput(second.getId(), 1))), ru.itmo.highload.common.security.TestTokens.bearer());
        assertThat(sameTotal.totalAmount()).isEqualByComparingTo("500.00");
        assertThat(sameTotal.version()).isEqualTo(increased.version() + 1);

        OrderResponse swapped = orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                sameTotal.version(), List.of(new OrderLineInput(first.getId(), 1),
                        new OrderLineInput(second.getId(), 2))), ru.itmo.highload.common.security.TestTokens.bearer());
        assertThat(swapped.totalAmount()).isEqualByComparingTo("500.00");
        assertThat(swapped.version()).isEqualTo(sameTotal.version() + 1);
        assertThat(swapped.lines().stream().map(line -> line.id()).toList())
                .containsExactlyInAnyOrderElementsOf(sameTotal.lines().stream().map(line -> line.id()).toList());
        OrderResponse persisted = orderService.getOrder(draft.id());
        assertThat(persisted.version()).isEqualTo(swapped.version());
        assertThat(persisted.lines()).containsExactlyInAnyOrderElementsOf(swapped.lines());
        assertThatThrownBy(() -> orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                sameTotal.version(), List.of(new OrderLineInput(first.getId(), 9))), ru.itmo.highload.common.security.TestTokens.bearer()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.getCode()).isEqualTo("ORDER_VERSION_CONFLICT"));

        OrderResponse replaced = orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                swapped.version(), List.of(new OrderLineInput(first.getId(), 2),
                        new OrderLineInput(third.getId(), 3))), ru.itmo.highload.common.security.TestTokens.bearer());
        assertThat(replaced.totalAmount()).isEqualByComparingTo("500.00");
        assertThat(replaced.version()).isEqualTo(swapped.version() + 1);
        assertThat(replaced.lines()).extracting(line -> line.dishId())
                .containsExactlyInAnyOrder(first.getId(), third.getId());
        assertThat(replaced.lines().stream().filter(line -> line.dishId().equals(first.getId())).findFirst()
                .orElseThrow().id()).isEqualTo(lineId);

        OrderResponse cleared = orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                replaced.version(), List.of()), ru.itmo.highload.common.security.TestTokens.bearer());
        assertThat(cleared.lines()).isEmpty();
        assertThat(cleared.totalAmount()).isEqualByComparingTo("0.00");
        assertThat(cleared.version()).isEqualTo(replaced.version() + 1);
        orderService.deleteEmptyDraft(draft.id());
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_line WHERE order_id = ?",
                Long.class, draft.id())).isZero();
    }

    @Test
    void failedQuantityUpdateRollsBackLinesTotalAndVersion() {
        OrderResponse draft = createDraft(fixture());
        Dish first = dish("Суп", "100.00");
        Dish second = dish("Горячее", "200.00");
        OrderResponse initial = orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                draft.version(), List.of(new OrderLineInput(first.getId(), 1))), ru.itmo.highload.common.security.TestTokens.bearer());
        jdbcTemplate.execute("ALTER TABLE order_line ADD CONSTRAINT ck_test_quantity CHECK (quantity < 5)");
        try {
            assertThatThrownBy(() -> orderService.replaceDraftLines(draft.id(), new ReplaceOrderLinesRequest(
                    initial.version(), List.of(new OrderLineInput(first.getId(), 5),
                            new OrderLineInput(second.getId(), 1))), ru.itmo.highload.common.security.TestTokens.bearer()))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE order_line DROP CONSTRAINT ck_test_quantity");
        }
        assertThat(orderService.getOrder(draft.id())).isEqualTo(initial);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM order_line WHERE order_id = ?",
                Long.class, draft.id())).isEqualTo(1L);
    }

    private Fixture fixture() {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Альфа", "+79991234567"));
        DeliveryPoint point = deliveryPointRepository.saveAndFlush(
                new DeliveryPoint(
                        organization,
                        "Главный офис",
                        "Кронверкский проспект, 49",
                        "Иван Петров",
                        "+79991234568"));
        return new Fixture(organization.getId(), point.getId());
    }

    private Dish dish(String name, String price) {
        return catalog.dish(name, price);
    }

    private OrderResponse createDraft(Fixture fixture) {
        return orderService.createDraft(new CreateOrderRequest(
                fixture.organizationId(),
                fixture.deliveryPointId(),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2),
                null));
    }

    private record Fixture(UUID organizationId, UUID deliveryPointId) {
    }
}
