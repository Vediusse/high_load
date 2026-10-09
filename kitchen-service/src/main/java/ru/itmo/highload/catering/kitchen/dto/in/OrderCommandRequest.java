package ru.itmo.highload.catering.kitchen.dto.in;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record OrderCommandRequest(
        @NotNull(message = "Ожидаемая версия обязательна")
        @PositiveOrZero(message = "Версия не может быть отрицательной")
        Long expectedVersion) {
}
