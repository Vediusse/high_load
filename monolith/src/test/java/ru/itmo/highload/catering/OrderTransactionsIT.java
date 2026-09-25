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
import ru.itmo.highload.catering.catalog.entity.Dish;
import ru.itmo.highload.catering.catalog.repository.DishRepository;
import ru.itmo.highload.catering.common.error.ApiException;
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
class OrderTransactionsIT extends AbstractPostgresIT {

    @Autowired
    OrderService orderService;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    DeliveryPointRepository deliveryPointRepository;

    @Autowired
    DishRepository dishRepository;

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
                        List.of(new OrderLineInput(oldDish.getId(), 2))));

        UUID missingDish = UUID.randomUUID();
        assertThatThrownBy(() -> orderService.replaceDraftLines(
                        draft.id(),
                        new ReplaceOrderLinesRequest(
                                initial.version(),
                                List.of(
                                        new OrderLineInput(newDish.getId(), 1),
                                        new OrderLineInput(missingDish, 1)))))
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
                                new OrderLineInput(laterInactiveDish.getId(), 2))));
        laterInactiveDish.deactivate();
        dishRepository.saveAndFlush(laterInactiveDish);

        assertThatThrownBy(() -> orderService.submit(draft.id(), withLines.version()))
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
                        List.of(new OrderLineInput(dish.getId(), 2))));

        dish.update("Борщ фирменный", "", new BigDecimal("200.00"), Set.of());
        dish = dishRepository.saveAndFlush(dish);
        OrderResponse submitted = orderService.submit(draft.id(), withLines.version());
        dish.update("Борщ новый", "", new BigDecimal("300.00"), Set.of());
        dishRepository.saveAndFlush(dish);

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
                        List.of(new OrderLineInput(dish.getId(), 2))));
        OrderResponse submitted = orderService.submit(draft.id(), withLines.version());

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
        return dishRepository.saveAndFlush(new Dish(name, "", new BigDecimal(price), Set.of()));
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
