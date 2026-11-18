package ru.itmo.highload.catering.order.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CorporateOrder {

    private UUID id;

    private UUID organizationId;

    private UUID deliveryPointId;

    private Instant requestedDeliveryAt;

    private OrderStatus status;

    private BigDecimal totalAmount;

    private String comment;

    private Instant createdAt;

    private long version;

    private List<OrderLine> lines = new ArrayList<>();

    private List<OrderStatusHistory> history = new ArrayList<>();

    public CorporateOrder(
            UUID organizationId,
            UUID deliveryPointId,
            Instant requestedDeliveryAt,
            String comment,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.organizationId = requireId(organizationId, "Организация обязательна");
        this.deliveryPointId = requireId(deliveryPointId, "Точка выдачи обязательна");
        this.requestedDeliveryAt =
                requireInstant(requestedDeliveryAt, "Время получения обязательно");
        this.comment = normalizeComment(comment);
        this.createdAt = requireInstant(createdAt, "Время создания обязательно");
        this.status = OrderStatus.DRAFT;
        this.totalAmount = new BigDecimal("0.00");
    }

    public void updateDetails(UUID deliveryPointId, Instant requestedDeliveryAt, String comment) {
        requireDraft("Детали заказа можно менять только в статусе DRAFT");
        this.deliveryPointId = requireId(deliveryPointId, "Точка выдачи обязательна");
        this.requestedDeliveryAt =
                requireInstant(requestedDeliveryAt, "Время получения обязательно");
        this.comment = normalizeComment(comment);
    }

    public void replaceLines(List<DraftLine> requestedLines) {
        requireDraft("Состав заказа можно менять только в статусе DRAFT");
        if (requestedLines == null) {
            throw new IllegalArgumentException("Список позиций обязателен");
        }

        Set<UUID> uniqueDishIds = new HashSet<>();
        BigDecimal preliminaryTotal = new BigDecimal("0.00");
        for (DraftLine requestedLine : requestedLines) {
            if (requestedLine == null) {
                throw new IllegalArgumentException("Позиция заказа не может быть null");
            }
            if (!uniqueDishIds.add(requestedLine.dishId())) {
                throw new DuplicateDishException(requestedLine.dishId());
            }
            preliminaryTotal =
                    preliminaryTotal.add(
                            requestedLine
                                    .currentPrice()
                                    .multiply(BigDecimal.valueOf(requestedLine.quantity())));
        }

        Map<UUID, OrderLine> existing = new HashMap<>();
        lines.forEach(line -> existing.put(line.getDishId(), line));
        List<OrderLine> replacements = new ArrayList<>(requestedLines.size());
        for (DraftLine requestedLine : requestedLines) {
            OrderLine line = existing.get(requestedLine.dishId());
            if (line == null) {
                line = new OrderLine(this, requestedLine.dishId(), requestedLine.quantity());
            } else {
                line.updateQuantity(requestedLine.quantity());
            }
            replacements.add(line);
        }

        // Replacing the collection also advances the order version when its total stays unchanged.
        lines.clear();
        lines.addAll(replacements);
        totalAmount = preliminaryTotal;
    }

    public void submit(Map<UUID, DishSnapshot> snapshots, Instant submittedAt) {
        submit(snapshots, submittedAt, null);
    }

    public void submit(Map<UUID, DishSnapshot> snapshots, Instant submittedAt, UUID changedBy) {
        requireDraft("Заказ можно отправить только из статуса DRAFT");
        if (lines.isEmpty()) {
            throw new EmptyOrderException();
        }
        if (!requestedDeliveryAt.isAfter(
                requireInstant(submittedAt, "Время отправки обязательно"))) {
            throw new DeliveryTimeNotFutureException();
        }
        for (OrderLine line : lines) {
            if (snapshots == null || !snapshots.containsKey(line.getDishId())) {
                throw new IllegalArgumentException("Для каждой позиции требуется снимок блюда");
            }
        }

        BigDecimal finalTotal = new BigDecimal("0.00");
        for (OrderLine line : lines) {
            DishSnapshot snapshot = snapshots.get(line.getDishId());
            line.captureSnapshot(snapshot.name(), snapshot.currentPrice());
            finalTotal = finalTotal.add(line.getLineAmount());
        }
        OrderStatus previousStatus = status;
        totalAmount = finalTotal;
        status = OrderStatus.SUBMITTED;
        history.add(
                new OrderStatusHistory(this, previousStatus, status, null, changedBy, submittedAt));
    }

    public void confirm(Instant changedAt) {
        confirm(changedAt, null);
    }

    public void confirm(Instant changedAt, UUID changedBy) {
        transitionFrom(
                OrderStatus.SUBMITTED,
                OrderStatus.CONFIRMED,
                null,
                changedAt,
                changedBy,
                "Подтвердить можно только заказ в статусе SUBMITTED");
    }

    public void reject(String reason, Instant changedAt) {
        reject(reason, changedAt, null);
    }

    public void reject(String reason, Instant changedAt, UUID changedBy) {
        if (status != OrderStatus.SUBMITTED) {
            throw new OrderStatusException("Отклонить можно только заказ в статусе SUBMITTED");
        }
        transitionTo(OrderStatus.REJECTED, normalizeRequiredReason(reason), changedAt, changedBy);
    }

    public void cancel(String reason, Instant changedAt) {
        cancel(reason, changedAt, null);
    }

    public void cancel(String reason, Instant changedAt, UUID changedBy) {
        if (status != OrderStatus.DRAFT
                && status != OrderStatus.SUBMITTED
                && status != OrderStatus.CONFIRMED) {
            throw new OrderStatusException(
                    "Отменить можно только заказ в статусе DRAFT, SUBMITTED или CONFIRMED");
        }
        transitionTo(OrderStatus.CANCELLED, normalizeRequiredReason(reason), changedAt, changedBy);
    }

    public void startCooking(Instant changedAt) {
        startCooking(changedAt, null);
    }

    public void startCooking(Instant changedAt, UUID changedBy) {
        transitionFrom(
                OrderStatus.CONFIRMED,
                OrderStatus.IN_COOKING,
                null,
                changedAt,
                changedBy,
                "Начать приготовление можно только для заказа в статусе CONFIRMED");
    }

    public void markReady(Instant changedAt) {
        markReady(changedAt, null);
    }

    public void markReady(Instant changedAt, UUID changedBy) {
        transitionFrom(
                OrderStatus.IN_COOKING,
                OrderStatus.READY,
                null,
                changedAt,
                changedBy,
                "Отметить готовность можно только для заказа в статусе IN_COOKING");
    }

    public void complete(Instant changedAt) {
        complete(changedAt, null);
    }

    public void complete(Instant changedAt, UUID changedBy) {
        transitionFrom(
                OrderStatus.READY,
                OrderStatus.COMPLETED,
                null,
                changedAt,
                changedBy,
                "Завершить заказ можно только из статуса READY");
    }

    public void requireDeletable() {
        requireDraft("Удалить можно только заказ в статусе DRAFT");
        if (!lines.isEmpty()) {
            throw new NonEmptyOrderDeleteException();
        }
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<OrderStatusHistory> getHistory() {
        return Collections.unmodifiableList(history);
    }

    private void requireDraft(String message) {
        if (status != OrderStatus.DRAFT) {
            throw new OrderStatusException(message);
        }
    }

    private void transitionFrom(
            OrderStatus expectedStatus,
            OrderStatus targetStatus,
            String reason,
            Instant changedAt,
            UUID changedBy,
            String conflictMessage) {
        if (status != expectedStatus) {
            throw new OrderStatusException(conflictMessage);
        }
        transitionTo(targetStatus, reason, changedAt, changedBy);
    }

    private void transitionTo(
            OrderStatus targetStatus, String reason, Instant changedAt, UUID changedBy) {
        Instant transitionTime = requireInstant(changedAt, "Время перехода обязательно");
        OrderStatus previousStatus = status;
        status = targetStatus;
        history.add(
                new OrderStatusHistory(
                        this, previousStatus, targetStatus, reason, changedBy, transitionTime));
    }

    private static UUID requireId(UUID id, String message) {
        if (id == null) {
            throw new IllegalArgumentException(message);
        }
        return id;
    }

    private static Instant requireInstant(Instant value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String normalizeComment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 1000) {
            throw new IllegalArgumentException("Комментарий не должен быть длиннее 1000 символов");
        }
        return trimmed;
    }

    private static String normalizeRequiredReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Причина обязательна");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 500) {
            throw new IllegalArgumentException("Причина не должна быть длиннее 500 символов");
        }
        return trimmed;
    }

    public record DraftLine(UUID dishId, int quantity, BigDecimal currentPrice) {
        public DraftLine {
            requireId(dishId, "Блюдо обязательно");
            if (quantity < 1 || quantity > 100000) {
                throw new IllegalArgumentException("Количество порций должно быть от 1 до 100000");
            }
            if (currentPrice == null || currentPrice.signum() <= 0 || currentPrice.scale() > 2) {
                throw new IllegalArgumentException("Текущая цена блюда должна быть положительной");
            }
        }
    }

    public record DishSnapshot(String name, BigDecimal currentPrice) {
        public DishSnapshot {
            if (name == null || name.isBlank() || name.trim().length() > 200) {
                throw new IllegalArgumentException("Название блюда для снимка некорректно");
            }
            if (currentPrice == null || currentPrice.signum() <= 0 || currentPrice.scale() > 2) {
                throw new IllegalArgumentException(
                        "Цена блюда для снимка должна быть положительной");
            }
        }
    }

    public static final class DuplicateDishException extends IllegalArgumentException {
        public DuplicateDishException(UUID dishId) {
            super("Блюдо " + dishId + " повторяется в заказе");
        }
    }

    public static final class EmptyOrderException extends IllegalStateException {
        public EmptyOrderException() {
            super("Нельзя отправить заказ без позиций");
        }
    }

    public static final class DeliveryTimeNotFutureException extends IllegalStateException {
        public DeliveryTimeNotFutureException() {
            super("Время получения должно находиться в будущем");
        }
    }

    public static final class OrderStatusException extends IllegalStateException {
        public OrderStatusException(String message) {
            super(message);
        }
    }

    public static final class NonEmptyOrderDeleteException extends IllegalStateException {
        public NonEmptyOrderDeleteException() {
            super("Нельзя удалить непустой черновик заказа");
        }
    }
}
