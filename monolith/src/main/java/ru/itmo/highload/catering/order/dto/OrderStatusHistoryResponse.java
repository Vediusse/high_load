package ru.itmo.highload.catering.order.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import ru.itmo.highload.catering.order.entity.OrderStatus;

public record OrderStatusHistoryResponse(
        UUID id,
        UUID orderId,
        OrderStatus fromStatus,
        OrderStatus toStatus,
        String reason,
        UUID changedBy,
        OffsetDateTime changedAt) {
}
