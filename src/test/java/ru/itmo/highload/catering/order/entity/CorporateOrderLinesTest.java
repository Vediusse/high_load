package ru.itmo.highload.catering.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorporateOrderLinesTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    @Test
    void replacesWholeDraftCompositionAndCalculatesPreliminaryTotal() {
        CorporateOrder order = draft(NOW.plusSeconds(3600));
        UUID soup = UUID.randomUUID();
        UUID main = UUID.randomUUID();

        order.replaceLines(List.of(
                new CorporateOrder.DraftLine(soup, 2, new BigDecimal("180.00")),
                new CorporateOrder.DraftLine(main, 3, new BigDecimal("320.00"))));

        assertThat(order.getLines()).hasSize(2);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("1320.00");
        assertThat(order.getLines()).allSatisfy(line -> {
            assertThat(line.getUnitPriceSnapshot()).isNull();
            assertThat(line.getLineAmount()).isNull();
        });
    }

    @Test
    void rejectsDuplicateDishWithoutChangingExistingComposition() {
        CorporateOrder order = draft(NOW.plusSeconds(3600));
        UUID existingDish = UUID.randomUUID();
        UUID duplicateDish = UUID.randomUUID();
        order.replaceLines(List.of(
                new CorporateOrder.DraftLine(existingDish, 2, new BigDecimal("100.00"))));

        assertThatThrownBy(() -> order.replaceLines(List.of(
                new CorporateOrder.DraftLine(duplicateDish, 1, new BigDecimal("200.00")),
                new CorporateOrder.DraftLine(duplicateDish, 2, new BigDecimal("200.00")))))
                .isInstanceOf(CorporateOrder.DuplicateDishException.class);

        assertThat(order.getLines()).singleElement()
                .satisfies(line -> assertThat(line.getDishId()).isEqualTo(existingDish));
        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.00");
    }

    @Test
    void validatesQuantityAndForbidsCompositionChangeAfterSubmit() {
        CorporateOrder order = draft(NOW.plusSeconds(3600));
        UUID dishId = UUID.randomUUID();

        assertThatThrownBy(() -> new CorporateOrder.DraftLine(dishId, 0, new BigDecimal("10.00")))
                .isInstanceOf(IllegalArgumentException.class);

        order.replaceLines(List.of(
                new CorporateOrder.DraftLine(dishId, 1, new BigDecimal("10.00"))));
        order.submit(
                Map.of(dishId, new CorporateOrder.DishSnapshot("Суп", new BigDecimal("10.00"))),
                NOW);

        assertThatThrownBy(() -> order.replaceLines(List.of()))
                .isInstanceOf(CorporateOrder.OrderStatusException.class);
    }

    private CorporateOrder draft(Instant deliveryAt) {
        return new CorporateOrder(UUID.randomUUID(), UUID.randomUUID(), deliveryAt, null, NOW.minusSeconds(60));
    }
}
