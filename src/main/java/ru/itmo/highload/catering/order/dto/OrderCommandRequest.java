package ru.itmo.highload.catering.order.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotNull;

public record OrderCommandRequest(
        @NotNull(message = "Ожидаемая версия обязательна")
        @PositiveOrZero(message = "Версия не может быть отрицательной")
        Long expectedVersion) {
}
