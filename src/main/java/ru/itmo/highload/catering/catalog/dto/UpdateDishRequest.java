package ru.itmo.highload.catering.catalog.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record UpdateDishRequest(
        @NotBlank(message = "Название блюда обязательно")
        @Size(max = 200, message = "Название блюда не должно быть длиннее 200 символов")
        String name,

        @NotNull(message = "Описание блюда обязательно")
        @Size(max = 2000, message = "Описание блюда не должно быть длиннее 2000 символов")
        String description,

        @NotNull(message = "Цена блюда обязательна")
        @DecimalMin(value = "0.00", inclusive = false, message = "Цена блюда должна быть больше нуля")
        @Digits(integer = 17, fraction = 2, message = "Цена должна содержать не более двух знаков после запятой")
        BigDecimal currentPrice,

        @NotNull(message = "Набор категорий обязателен")
        Set<@Valid @NotNull(message = "Идентификатор категории не может быть null") UUID> categoryIds) {
}
