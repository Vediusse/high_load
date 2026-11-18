package ru.itmo.highload.catering.kitchen.domain.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record KitchenObservation(UUID orderId, OrderStatus status, long version) {
    public KitchenObservation {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(status);
        if (version < 0) throw new IllegalArgumentException("Negative source version");
    }

    public boolean isCookingState() {
        return Set.of(
                        OrderStatus.CONFIRMED,
                        OrderStatus.IN_COOKING,
                        OrderStatus.READY,
                        OrderStatus.COMPLETED)
                .contains(status);
    }
}
