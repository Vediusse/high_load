package ru.itmo.highload.catering.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record OrderLineInput(
        @NotNull(message = "Блюдо обязательно")
        UUID dishId,

        @Min(value = 1, message = "Количество должно быть не меньше 1")
        @Max(value = 100000, message = "Количество не должно быть больше 100000")
        int quantity) {
}
