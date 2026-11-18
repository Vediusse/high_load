package ru.itmo.highload.catering.kitchen.application.dto.in;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenAction;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import ru.itmo.highload.catering.kitchen.domain.model.OrderStatus;

public record KitchenCommand(
        @NotNull UUID commandId,
        @NotNull OrderStatus expectedStatus,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull KitchenAction action) {
}
