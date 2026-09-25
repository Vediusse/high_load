package ru.itmo.highload.catering.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import ru.itmo.highload.catering.order.entity.OrderStatus;

public record KitchenCommand(
        @NotNull UUID commandId,
        @NotNull OrderStatus expectedStatus,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull KitchenAction action) {
}
