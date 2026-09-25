package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.itmo.highload.catering.order.dto.*;
import ru.itmo.highload.catering.order.entity.OrderStatus;
import ru.itmo.highload.catering.order.service.*;
import ru.itmo.highload.catering.organization.entity.*;
import ru.itmo.highload.catering.organization.repository.*;
import ru.itmo.highload.common.error.ApiException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
class KitchenCommandsIT extends AbstractPostgresIT {
    @Autowired OrderService orders;
    @Autowired KitchenCommands commands;
    @Autowired OrganizationRepository organizations;
    @Autowired DeliveryPointRepository points;
    @Autowired WebTestClient client;

    @Test
    void stateBatchIsBoundedAndReturnsOnlyOwnerState() {
        var first = confirmed();
        var second = confirmed();
        client.post().uri("/internal/v1/orders/states")
                .bodyValue(Map.of("ids", List.of(first.id(), second.id())))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[*].status").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("CONFIRMED")))
                .jsonPath("$[0].lines").doesNotExist();
        for (var ids : List.of(List.of(), java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> UUID.randomUUID()).toList())) {
            client.post().uri("/internal/v1/orders/states").bodyValue(Map.of("ids", ids))
                    .exchange().expectStatus().isBadRequest();
        }
        client.post().uri("/internal/v1/orders/states")
                .bodyValue(Map.of("ids", List.of(first.id(), UUID.randomUUID())))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void concurrentDuplicatesExecuteOnceAndReplayOriginalResultAfterFurtherTransitions() throws Exception {
        var order = confirmed();
        var command = start(order);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var barrier = new CyclicBarrier(4);
            var calls = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return commands.execute(order.id(), command);
            })).toList();
            for (var call : calls) assertThat(call.get(10, TimeUnit.SECONDS).status()).isEqualTo(OrderStatus.IN_COOKING);
        }
        var cooking = orders.getOrder(order.id());
        var ready = commands.execute(order.id(), new KitchenCommand(UUID.randomUUID(), OrderStatus.IN_COOKING,
                cooking.version(), KitchenAction.MARK_READY));
        commands.execute(order.id(), new KitchenCommand(UUID.randomUUID(), OrderStatus.READY,
                ready.version(), KitchenAction.COMPLETE));
        assertThat(commands.execute(order.id(), command)).isEqualTo(cooking);
        assertThat(orders.getOrder(order.id()).status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(count("kitchen_command", order.id())).isEqualTo(3);
        assertThat(count("order_status_history", order.id())).isEqualTo(5);
    }

    @Test
    void sameCommandIdCannotChangePayloadOrOrder() {
        var order = confirmed();
        var command = start(order);
        commands.execute(order.id(), command);
        for (var changed : List.of(
                new KitchenCommand(command.commandId(), OrderStatus.CONFIRMED, order.version()+1, command.action()),
                new KitchenCommand(command.commandId(), OrderStatus.READY, order.version(), command.action()),
                new KitchenCommand(command.commandId(), OrderStatus.CONFIRMED, order.version(), KitchenAction.COMPLETE))) {
            assertThatThrownBy(() -> commands.execute(order.id(), changed)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.getCode()).isEqualTo("COMMAND_ID_CONFLICT"));
        }
        assertThatThrownBy(() -> commands.execute(UUID.randomUUID(), command)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getCode()).isEqualTo("COMMAND_ID_CONFLICT"));
        assertThat(count("kitchen_command", order.id())).isEqualTo(1);
    }

    @Test
    void differentCommandsForOneVersionHaveOnlyOneWinner() throws Exception {
        var order = confirmed();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            Callable<String> task = () -> {
                barrier.await(5, TimeUnit.SECONDS);
                try { commands.execute(order.id(), start(order)); return "OK"; }
                catch (ApiException error) { return error.getCode(); }
            };
            var first = pool.submit(task); var second = pool.submit(task);
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "ORDER_VERSION_CONFLICT");
        }
        assertThat(count("kitchen_command", order.id())).isEqualTo(1);
        assertThat(count("order_status_history", order.id())).isEqualTo(3);
    }

    @Test
    void failedReceiptWriteRollsBackAggregateAndHistoryAndCanBeRetried() {
        var order = confirmed(); var command = start(order);
        jdbcTemplate.execute("alter table kitchen_command add constraint reject_test_receipt check (expected_version < 0)");
        try {
            assertThatThrownBy(() -> commands.execute(order.id(), command)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(orders.getOrder(order.id())).isEqualTo(order);
            assertThat(count("order_status_history", order.id())).isEqualTo(2);
            assertThat(count("kitchen_command", order.id())).isZero();
        } finally { jdbcTemplate.execute("alter table kitchen_command drop constraint reject_test_receipt"); }
        assertThat(commands.execute(order.id(), command).status()).isEqualTo(OrderStatus.IN_COOKING);
    }

    @Test
    void internalApiValidatesCommandsAndQueueWhilePublicWritePathIsGone() {
        var order = confirmed();
        client.get().uri("/internal/v1/orders/kitchen?size=1").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].id").isEqualTo(order.id().toString());
        client.get().uri("/internal/v1/orders/kitchen?size=51").exchange().expectStatus().isBadRequest();
        client.get().uri("/internal/v1/orders/"+order.id()).exchange().expectStatus().isOk();
        client.post().uri("/internal/v1/orders/"+order.id()+"/kitchen-commands").bodyValue(Map.of())
                .exchange().expectStatus().isBadRequest();
        client.post().uri("/internal/v1/orders/"+order.id()+"/kitchen-commands")
                .bodyValue(new KitchenCommand(UUID.randomUUID(), OrderStatus.READY, order.version(), KitchenAction.START_COOKING))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("ORDER_STATUS_CONFLICT");
        client.post().uri("/api/v1/orders/"+order.id()+"/start-cooking").bodyValue(Map.of("expectedVersion",order.version()))
                .exchange().expectStatus().isNotFound();
        assertThat(count("kitchen_command", order.id())).isZero();
    }

    private KitchenCommand start(OrderResponse order) {
        return new KitchenCommand(UUID.randomUUID(), OrderStatus.CONFIRMED, order.version(), KitchenAction.START_COOKING);
    }
    private int count(String table, UUID id) {
        return jdbcTemplate.queryForObject("select count(*) from "+table+" where order_id=?", Integer.class,id);
    }
    private OrderResponse confirmed() {
        var org = organizations.saveAndFlush(new Organization("Кухня", "+79991234567"));
        var point = points.saveAndFlush(new DeliveryPoint(org,"Офис","Адрес","Иван","+79991234567"));
        var dish = catalog.dish("Суп", "180.00");
        var draft = orders.createDraft(new CreateOrderRequest(org.getId(),point.getId(),OffsetDateTime.now().plusDays(2),null));
        var lines = orders.replaceDraftLines(draft.id(),new ReplaceOrderLinesRequest(draft.version(),List.of(new OrderLineInput(dish.getId(),2))));
        var submitted = orders.submit(draft.id(),lines.version());
        return orders.confirm(draft.id(),submitted.version());
    }
}
