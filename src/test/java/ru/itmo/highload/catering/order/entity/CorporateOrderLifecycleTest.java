package ru.itmo.highload.catering.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorporateOrderLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    @Test
    void confirmedOrderCompletesOnlyThroughEveryKitchenStage() {
        CorporateOrder order = submittedOrder();

        order.confirm(NOW.plusSeconds(1));
        order.startCooking(NOW.plusSeconds(2));
        order.markReady(NOW.plusSeconds(3));
        order.complete(NOW.plusSeconds(4));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getHistory())
                .extracting(OrderStatusHistory::getToStatus)
                .containsExactly(
                        OrderStatus.SUBMITTED,
                        OrderStatus.CONFIRMED,
                        OrderStatus.IN_COOKING,
                        OrderStatus.READY,
                        OrderStatus.COMPLETED);
        assertThat(order.getHistory()).allSatisfy(history -> {
            assertThat(history.getReason()).isNull();
            assertThat(history.getChangedBy()).isNull();
        });
    }

    @Test
    void rejectionIsWholeOrderTerminalAndStoresTrimmedReason() {
        CorporateOrder order = submittedOrder();

        order.reject("  кухня закрыта  ", NOW.plusSeconds(1));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getHistory()).last().satisfies(history -> {
            assertThat(history.getFromStatus()).isEqualTo(OrderStatus.SUBMITTED);
            assertThat(history.getToStatus()).isEqualTo(OrderStatus.REJECTED);
            assertThat(history.getReason()).isEqualTo("кухня закрыта");
        });
        assertThatThrownBy(() -> order.confirm(NOW.plusSeconds(2)))
                .isInstanceOf(CorporateOrder.OrderStatusException.class);
        assertThat(order.getHistory()).hasSize(2);
    }

    @Test
    void cancellationIsAllowedBeforeCookingFromEverySpecifiedStatus() {
        CorporateOrder draft = draft();
        CorporateOrder submitted = submittedOrder();
        CorporateOrder confirmed = submittedOrder();
        confirmed.confirm(NOW.plusSeconds(1));

        draft.cancel("не нужен", NOW.plusSeconds(2));
        submitted.cancel("перенос", NOW.plusSeconds(2));
        confirmed.cancel("офис закрыт", NOW.plusSeconds(2));

        assertThat(List.of(draft, submitted, confirmed))
                .extracting(CorporateOrder::getStatus)
                .containsOnly(OrderStatus.CANCELLED);
        assertThat(draft.getHistory()).singleElement().satisfies(history ->
                assertThat(history.getFromStatus()).isEqualTo(OrderStatus.DRAFT));
        assertThat(submitted.getHistory()).last().satisfies(history ->
                assertThat(history.getFromStatus()).isEqualTo(OrderStatus.SUBMITTED));
        assertThat(confirmed.getHistory()).last().satisfies(history ->
                assertThat(history.getFromStatus()).isEqualTo(OrderStatus.CONFIRMED));
    }

    @Test
    void cancellationAfterCookingIsRejectedWithoutMutation() {
        CorporateOrder order = submittedOrder();
        order.confirm(NOW.plusSeconds(1));
        order.startCooking(NOW.plusSeconds(2));
        int historySize = order.getHistory().size();

        assertThatThrownBy(() -> order.cancel("слишком поздно", NOW.plusSeconds(3)))
                .isInstanceOf(CorporateOrder.OrderStatusException.class)
                .hasMessageContaining("DRAFT, SUBMITTED или CONFIRMED");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.IN_COOKING);
        assertThat(order.getHistory()).hasSize(historySize);
    }

    @Test
    void invalidSkippedAndRepeatedTransitionsDoNotCreateHistory() {
        CorporateOrder order = submittedOrder();
        int initialHistorySize = order.getHistory().size();

        assertThatThrownBy(() -> order.startCooking(NOW.plusSeconds(1)))
                .isInstanceOf(CorporateOrder.OrderStatusException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(order.getHistory()).hasSize(initialHistorySize);

        order.confirm(NOW.plusSeconds(2));
        assertThatThrownBy(() -> order.confirm(NOW.plusSeconds(3)))
                .isInstanceOf(CorporateOrder.OrderStatusException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getHistory()).hasSize(initialHistorySize + 1);
    }

    @Test
    void rejectionAndCancellationRequireReasonAtDomainBoundary() {
        CorporateOrder submitted = submittedOrder();
        CorporateOrder draft = draft();

        assertThatThrownBy(() -> draft.reject("нет мощности", NOW.plusSeconds(1)))
                .isInstanceOf(CorporateOrder.OrderStatusException.class);
        assertThatThrownBy(() -> submitted.reject(" ", NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Причина обязательна");
        assertThatThrownBy(() -> draft.cancel(null, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Причина обязательна");
        assertThat(submitted.getStatus()).isEqualTo(OrderStatus.SUBMITTED);
        assertThat(submitted.getHistory()).hasSize(1);
        assertThat(draft.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(draft.getHistory()).isEmpty();
    }

    private CorporateOrder submittedOrder() {
        CorporateOrder order = draft();
        UUID dishId = UUID.randomUUID();
        order.replaceLines(List.of(
                new CorporateOrder.DraftLine(dishId, 2, new BigDecimal("180.00"))));
        order.submit(
                Map.of(dishId, new CorporateOrder.DishSnapshot("Борщ", new BigDecimal("180.00"))),
                NOW);
        return order;
    }

    private CorporateOrder draft() {
        return new CorporateOrder(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NOW.plusSeconds(3600),
                null,
                NOW.minusSeconds(60));
    }
}
