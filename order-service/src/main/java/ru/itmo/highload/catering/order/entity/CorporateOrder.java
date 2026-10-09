package ru.itmo.highload.catering.order.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

@Entity
@Table(name = "corporate_order")
public class CorporateOrder {

    @Id
    private UUID id;

    @NotNull
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @NotNull
    @Column(name = "delivery_point_id", nullable = false)
    private UUID deliveryPointId;

    @NotNull
    @Column(name = "requested_delivery_at", nullable = false)
    private Instant requestedDeliveryAt;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @NotNull
    @DecimalMin("0.00")
    @Digits(integer = 17, fraction = 2)
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Size(max = 1000)
    @Column(length = 1000)
    private String comment;

    @NotNull
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Valid
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @org.hibernate.annotations.OptimisticLock(excluded = false)
    @OrderBy("id ASC")
    private List<OrderLine> lines = new ArrayList<>();

    @Valid
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("changedAt ASC, id ASC")
    private List<OrderStatusHistory> history = new ArrayList<>();

    protected CorporateOrder() {
    }

    public CorporateOrder(
            UUID organizationId,
            UUID deliveryPointId,
            Instant requestedDeliveryAt,
            String comment,
            Instant createdAt) {
        this.id = UUID.randomUUID();
        this.organizationId = requireId(organizationId, "Организация обязательна");
        this.deliveryPointId = requireId(deliveryPointId, "Точка выдачи обязательна");
        this.requestedDeliveryAt = requireInstant(requestedDeliveryAt, "Время получения обязательно");
        this.comment = normalizeComment(comment);
        this.createdAt = requireInstant(createdAt, "Время создания обязательно");
        this.status = OrderStatus.DRAFT;
        this.totalAmount = new BigDecimal("0.00");
    }

    public void updateDetails(UUID deliveryPointId, Instant requestedDeliveryAt, String comment) {
        requireDraft("Детали заказа можно менять только в статусе DRAFT");
        this.deliveryPointId = requireId(deliveryPointId, "Точка выдачи обязательна");
        this.requestedDeliveryAt = requireInstant(requestedDeliveryAt, "Время получения обязательно");
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
            preliminaryTotal = preliminaryTotal.add(
                    requestedLine.currentPrice().multiply(BigDecimal.valueOf(requestedLine.quantity())));
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
        requireDraft("Заказ можно отправить только из статуса DRAFT");
        if (lines.isEmpty()) {
            throw new EmptyOrderException();
        }
        if (!requestedDeliveryAt.isAfter(requireInstant(submittedAt, "Время отправки обязательно"))) {
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
        history.add(new OrderStatusHistory(this, previousStatus, status, null, null, submittedAt));
    }

    public void confirm(Instant changedAt) {
        transitionFrom(
                OrderStatus.SUBMITTED,
                OrderStatus.CONFIRMED,
                null,
                changedAt,
                "Подтвердить можно только заказ в статусе SUBMITTED");
    }

    public void reject(String reason, Instant changedAt) {
        if (status != OrderStatus.SUBMITTED) {
            throw new OrderStatusException("Отклонить можно только заказ в статусе SUBMITTED");
        }
        transitionTo(OrderStatus.REJECTED, normalizeRequiredReason(reason), changedAt);
    }

    public void cancel(String reason, Instant changedAt) {
        if (status != OrderStatus.DRAFT
                && status != OrderStatus.SUBMITTED
                && status != OrderStatus.CONFIRMED) {
            throw new OrderStatusException(
                    "Отменить можно только заказ в статусе DRAFT, SUBMITTED или CONFIRMED");
        }
        transitionTo(OrderStatus.CANCELLED, normalizeRequiredReason(reason), changedAt);
    }

    public void startCooking(Instant changedAt) {
        transitionFrom(
                OrderStatus.CONFIRMED,
                OrderStatus.IN_COOKING,
                null,
                changedAt,
                "Начать приготовление можно только для заказа в статусе CONFIRMED");
    }

    public void markReady(Instant changedAt) {
        transitionFrom(
                OrderStatus.IN_COOKING,
                OrderStatus.READY,
                null,
                changedAt,
                "Отметить готовность можно только для заказа в статусе IN_COOKING");
    }

    public void complete(Instant changedAt) {
        transitionFrom(
                OrderStatus.READY,
                OrderStatus.COMPLETED,
                null,
                changedAt,
                "Завершить заказ можно только из статуса READY");
    }

    public void requireDeletable() {
        requireDraft("Удалить можно только заказ в статусе DRAFT");
        if (!lines.isEmpty()) {
            throw new NonEmptyOrderDeleteException();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getDeliveryPointId() {
        return deliveryPointId;
    }

    public Instant getRequestedDeliveryAt() {
        return requestedDeliveryAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
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
            String conflictMessage) {
        if (status != expectedStatus) {
            throw new OrderStatusException(conflictMessage);
        }
        transitionTo(targetStatus, reason, changedAt);
    }

    private void transitionTo(OrderStatus targetStatus, String reason, Instant changedAt) {
        Instant transitionTime = requireInstant(changedAt, "Время перехода обязательно");
        OrderStatus previousStatus = status;
        status = targetStatus;
        history.add(new OrderStatusHistory(this, previousStatus, targetStatus, reason, null, transitionTime));
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
                throw new IllegalArgumentException("Цена блюда для снимка должна быть положительной");
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
