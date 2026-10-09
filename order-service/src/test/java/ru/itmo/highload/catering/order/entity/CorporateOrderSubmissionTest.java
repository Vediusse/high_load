package ru.itmo.highload.catering.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorporateOrderSubmissionTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    @Test
    void submitCapturesNamesPricesTotalAndSingleHistoryEntry() {
        CorporateOrder order = draft(NOW.plusSeconds(3600));
        UUID soup = UUID.randomUUID();
        UUID main = UUID.randomUUID();
        order.replaceLines(List.of(
                new CorporateOrder.DraftLine(soup, 25, new BigDecimal("170.00")),
                new CorporateOrder.DraftLine(main, 30, new BigDecimal("310.00"))));

        order.submit(Map.of(
                soup, new CorporateOrder.DishSnapshot("Борщ", new BigDecimal("180.00")),
                main, new CorporateOrder.DishSnapshot("Котлета", new BigDecimal("320.00"))), NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("14100.00");
        assertThat(order.getLines())
                .extracting(OrderLine::getDishNameSnapshot)
                .containsExactlyInAnyOrder("Борщ", "Котлета");
        assertThat(order.getLines())
                .extracting(OrderLine::getUnitPriceSnapshot)
                .containsExactlyInAnyOrder(new BigDecimal("180.00"), new BigDecimal("320.00"));
        assertThat(order.getHistory()).singleElement().satisfies(history -> {
            assertThat(history.getFromStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(history.getToStatus()).isEqualTo(OrderStatus.SUBMITTED);
            assertThat(history.getChangedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void emptyOrderCannotBeSubmitted() {
        CorporateOrder order = draft(NOW.plusSeconds(3600));

        assertThatThrownBy(() -> order.submit(Map.of(), NOW))
                .isInstanceOf(CorporateOrder.EmptyOrderException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getHistory()).isEmpty();
    }

    @Test
    void deliveryTimeMustStillBeFutureAtSubmit() {
        CorporateOrder order = draft(NOW.minusSeconds(1));
        UUID dishId = UUID.randomUUID();
        order.replaceLines(List.of(
                new CorporateOrder.DraftLine(dishId, 1, new BigDecimal("100.00"))));

        assertThatThrownBy(() -> order.submit(
                        Map.of(dishId, new CorporateOrder.DishSnapshot("Суп", new BigDecimal("100.00"))),
                        NOW))
                .isInstanceOf(CorporateOrder.DeliveryTimeNotFutureException.class);
        assertThat(order.getLines()).allSatisfy(line -> assertThat(line.getUnitPriceSnapshot()).isNull());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void nonEmptyDraftCannotBeDeleted() {
        CorporateOrder order = draft(NOW.plusSeconds(3600));
        order.replaceLines(List.of(
                new CorporateOrder.DraftLine(UUID.randomUUID(), 1, new BigDecimal("100.00"))));

        assertThatThrownBy(order::requireDeletable)
                .isInstanceOf(CorporateOrder.NonEmptyOrderDeleteException.class);
    }

    private CorporateOrder draft(Instant deliveryAt) {
        return new CorporateOrder(UUID.randomUUID(), UUID.randomUUID(), deliveryAt, " Тест ", NOW.minusSeconds(60));
    }
}
