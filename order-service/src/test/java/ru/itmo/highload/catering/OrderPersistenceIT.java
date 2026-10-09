package ru.itmo.highload.catering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import ru.itmo.highload.catering.CatalogFixture.Dish;
import ru.itmo.highload.catering.order.dto.CreateOrderRequest;
import ru.itmo.highload.catering.order.dto.OrderLineInput;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.ReplaceOrderLinesRequest;
import ru.itmo.highload.catering.order.entity.OrderLine;
import ru.itmo.highload.catering.order.repository.CorporateOrderRepository;
import ru.itmo.highload.catering.order.service.OrderService;
import ru.itmo.highload.catering.organization.entity.DeliveryPoint;
import ru.itmo.highload.catering.organization.entity.Organization;
import ru.itmo.highload.catering.organization.repository.DeliveryPointRepository;
import ru.itmo.highload.catering.organization.repository.OrganizationRepository;

@SpringBootTest
class OrderPersistenceIT extends AbstractPostgresIT {

    @Autowired
    OrderService orderService;

    @Autowired
    CorporateOrderRepository orderRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Autowired
    DeliveryPointRepository deliveryPointRepository;


    @Test
    void enumAndThreeOrderRelationsArePersistedAndHistoryIsAppendOnly() {
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
        assertThat(orderRepository.findDetailedById(draft.id())).hasValueSatisfying(order ->
                assertThat(order.getLines()).isEmpty());
        OrderResponse withLines = orderService.replaceDraftLines(
                draft.id(),
                new ReplaceOrderLinesRequest(
                        draft.version(),
                        List.of(new OrderLineInput(dish.getId(), 2))));
        assertThat(orderRepository.findDetailedById(draft.id())).hasValueSatisfying(order ->
                assertThat(order.getLines()).extracting(OrderLine::getDishId).containsExactly(dish.getId()));
        orderService.submit(draft.id(), withLines.version());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM corporate_order WHERE id = ?",
                String.class,
                draft.id())).isEqualTo("SUBMITTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_line WHERE order_id = ? AND dish_id = ?",
                Long.class,
                draft.id(),
                dish.getId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_status FROM order_status_history WHERE order_id = ?",
                String.class,
                draft.id())).isEqualTo("SUBMITTED");

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE order_status_history SET reason = 'изменено' WHERE order_id = ?",
                        draft.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "DELETE FROM order_status_history WHERE order_id = ?",
                        draft.id()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsInvalidQuantityAndDuplicateDish() {
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

        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO order_line (id, order_id, dish_id, quantity)
                        VALUES (?, ?, ?, 0)
                        """,
                java.util.UUID.randomUUID(), draft.id(), dish.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbcTemplate.update("""
                        INSERT INTO order_line (id, order_id, dish_id, quantity)
                        VALUES (?, ?, ?, 1)
                        """,
                java.util.UUID.randomUUID(), draft.id(), dish.getId());
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        INSERT INTO order_line (id, order_id, dish_id, quantity)
                        VALUES (?, ?, ?, 2)
                        """,
                java.util.UUID.randomUUID(), draft.id(), dish.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsInvalidHistoryTransitionAndReasonShape() {
        Organization organization = organizationRepository.saveAndFlush(
                new Organization("Альфа", "+79991234567"));
        DeliveryPoint point = deliveryPointRepository.saveAndFlush(new DeliveryPoint(
                organization,
                "Главный офис",
                "Адрес",
                "Иван",
                "+79991234568"));
        OrderResponse draft = orderService.createDraft(new CreateOrderRequest(
                organization.getId(),
                point.getId(),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2),
                null));

        assertThatThrownBy(() -> insertHistory(draft, "DRAFT", "READY", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertHistory(draft, "DRAFT", "CANCELLED", " "))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertHistory(draft, "DRAFT", "SUBMITTED", "лишняя причина"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_status_history WHERE order_id = ?",
                Long.class,
                draft.id())).isZero();
    }

    private void insertHistory(OrderResponse order, String fromStatus, String toStatus, String reason) {
        jdbcTemplate.update("""
                        INSERT INTO order_status_history (
                            id, order_id, from_status, to_status, reason, changed_at
                        ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                java.util.UUID.randomUUID(),
                order.id(),
                fromStatus,
                toStatus,
                reason);
    }
}
