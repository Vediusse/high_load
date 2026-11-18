package ru.itmo.highload.catering.order.domain.model;

import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {

    private UUID id;

    @Getter(AccessLevel.NONE)
    private CorporateOrder order;

    private OrderStatus fromStatus;

    private OrderStatus toStatus;

    private String reason;

    private UUID changedBy;

    private Instant changedAt;

    OrderStatusHistory(
            CorporateOrder order,
            OrderStatus fromStatus,
            OrderStatus toStatus,
            String reason,
            UUID changedBy,
            Instant changedAt) {
        if (order == null || fromStatus == null || toStatus == null || changedAt == null) {
            throw new IllegalArgumentException("Переход статуса и время обязательны");
        }
        this.id = UUID.randomUUID();
        this.order = order;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

    public UUID getOrderId() {
        return order.getId();
    }
}
