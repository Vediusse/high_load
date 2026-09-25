package ru.itmo.highload.catering.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UpdateOrderDetailsRequest(
        @NotNull(message = "Точка выдачи обязательна")
        UUID deliveryPointId,

        @NotNull(message = "Время получения обязательно")
        OffsetDateTime requestedDeliveryAt,

        @Size(max = 1000, message = "Комментарий не должен быть длиннее 1000 символов")
        String comment,

        @NotNull(message = "Ожидаемая версия обязательна")
        @PositiveOrZero(message = "Версия не может быть отрицательной")
        Long expectedVersion) {
}
