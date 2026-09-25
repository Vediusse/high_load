package ru.itmo.highload.catering.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public record ReplaceOrderLinesRequest(
        @NotNull(message = "Ожидаемая версия обязательна")
        @PositiveOrZero(message = "Версия не может быть отрицательной")
        Long expectedVersion,

        @NotNull(message = "Список позиций обязателен")
        List<@Valid @NotNull(message = "Позиция не может быть null") OrderLineInput> lines) {
}
