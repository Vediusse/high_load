package ru.itmo.highload.catering.kitchen.client.dto.out;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderStatus;

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
