package ru.itmo.highload.catering.order.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ru.itmo.highload.catering.order.entity.OrderStatus;

public record OrderResponse(
        UUID id,
        UUID organizationId,
        UUID deliveryPointId,
        OffsetDateTime requestedDeliveryAt,
        OrderStatus status,
        BigDecimal totalAmount,
        String comment,
        long version,
        OffsetDateTime createdAt,
        List<OrderLineResponse> lines) {
}
