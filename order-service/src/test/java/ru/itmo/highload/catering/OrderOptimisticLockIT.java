package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.itmo.highload.common.error.ApiException;
import ru.itmo.highload.catering.CatalogFixture.Dish;
import ru.itmo.highload.catering.order.dto.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.OrderLineInput;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.RejectOrderRequest;
import ru.itmo.highload.catering.order.dto.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.dto.UpdateOrderDetailsRequest;
import ru.itmo.highload.catering.order.service.OrderService;
import ru.itmo.highload.catering.organization.entity.DeliveryPoint;
import ru.itmo.highload.catering.organization.entity.Organization;
import ru.itmo.highload.catering.organization.repository.DeliveryPointRepository;
import ru.itmo.highload.catering.organization.repository.OrganizationRepository;

@SpringBootTest
class OrderOptimisticLockIT extends AbstractPostgresIT {

    @Autowired
    OrderService orderService;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    DeliveryPointRepository deliveryPointRepository;


    @Test
    void twoCommandsWithSameVersionProduceOneSuccessAndOneConflict() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Альфа", "+79991234567"));
        DeliveryPoint point = deliveryPointRepository.saveAndFlush(new DeliveryPoint(
                organization,
                "Главный офис",
                "Адрес",
                "Иван",
                "+79991234568"));
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        OrderResponse draft = orderService.createDraft(new CreateOrderRequest(
                organization.getId(), point.getId(), future, null));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> first = command(ready, start, draft, point.getId(), future, "Первая команда");
        Callable<String> second = command(ready, start, draft, point.getId(), future, "Вторая команда");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstResult = executor.submit(first);
            Future<String> secondResult = executor.submit(second);
            ready.await();
            start.countDown();

            assertThat(List.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder("SUCCESS", "ORDER_VERSION_CONFLICT");
        } finally {
            executor.shutdownNow();
        }

        OrderResponse stored = orderService.getOrder(draft.id());
        assertThat(stored.version()).isEqualTo(draft.version() + 1);
        assertThat(stored.comment()).isIn("Первая команда", "Вторая команда");
    }

    @Test
    void concurrentKitchenDecisionsCreateExactlyOneDecisionAndOneHistoryRow() throws Exception {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Альфа", "+79991234567"));
        DeliveryPoint point = deliveryPointRepository.saveAndFlush(new DeliveryPoint(
                organization,
                "Главный офис",
                "Адрес",
                "Иван",
                "+79991234568"));
        Dish dish = catalog.dish("Борщ", "180.00");
        OrderResponse draft = orderService.createDraft(new CreateOrderRequest(
                organization.getId(),
                point.getId(),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2),
                null));
        OrderResponse withLines = orderService.replaceDraftLines(
                draft.id(),
                new ReplaceOrderLinesRequest(
                        draft.version(),
                        List.of(new OrderLineInput(dish.getId(), 2))));
        OrderResponse submitted = orderService.submit(draft.id(), withLines.version());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> confirm = kitchenDecision(ready, start, submitted, true);
        Callable<String> reject = kitchenDecision(ready, start, submitted, false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> confirmResult = executor.submit(confirm);
            Future<String> rejectResult = executor.submit(reject);
            ready.await();
            start.countDown();

            assertThat(List.of(confirmResult.get(), rejectResult.get()))
                    .containsExactlyInAnyOrder("SUCCESS", "ORDER_VERSION_CONFLICT");
        } finally {
            executor.shutdownNow();
        }

        OrderResponse stored = orderService.getOrder(submitted.id());
        assertThat(stored.status().name()).isIn("CONFIRMED", "REJECTED");
        assertThat(stored.version()).isEqualTo(submitted.version() + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                submitted.id())).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ? AND from_status = 'SUBMITTED'",
                Long.class,
                submitted.id())).isEqualTo(1L);
    }

    private Callable<String> command(
            CountDownLatch ready,
            CountDownLatch start,
            OrderResponse draft,
            UUID pointId,
            OffsetDateTime future,
            String comment) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                orderService.updateDraftDetails(
                        draft.id(),
                        new UpdateOrderDetailsRequest(
                                pointId,
                                future.plusHours(1),
                                comment,
                                draft.version()));
                return "SUCCESS";
            } catch (ApiException exception) {
                return exception.getCode();
            }
        };
    }

    private Callable<String> kitchenDecision(
            CountDownLatch ready,
            CountDownLatch start,
            OrderResponse submitted,
            boolean confirm) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                if (confirm) {
                    orderService.confirm(submitted.id(), submitted.version());
                } else {
                    orderService.reject(
                            submitted.id(),
                            new RejectOrderRequest(submitted.version(), "нет мощности"));
                }
                return "SUCCESS";
            } catch (ApiException exception) {
                return exception.getCode();
            }
        };
    }
}
