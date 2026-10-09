package ru.itmo.highload.catering.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RejectOrderRequest(
        @NotNull(message = "Ожидаемая версия обязательна")
        @PositiveOrZero(message = "Версия не может быть отрицательной")
        Long expectedVersion,

        @NotBlank(message = "Причина отклонения обязательна")
        @Size(max = 500, message = "Причина отклонения не должна быть длиннее 500 символов")
        String reason) {
}
