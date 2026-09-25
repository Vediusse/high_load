package ru.itmo.highload.catering.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateDeliveryPointRequest(
        @NotBlank(message = "Название точки выдачи обязательно")
        @Size(max = 200, message = "Название точки выдачи не должно быть длиннее 200 символов")
        String name,

        @NotBlank(message = "Адрес обязателен")
        @Size(max = 500, message = "Адрес не должен быть длиннее 500 символов")
        String address,

        @NotBlank(message = "Контактное лицо обязательно")
        @Size(max = 200, message = "Контактное лицо не должно быть длиннее 200 символов")
        String contactName,

        @NotBlank(message = "Контактный телефон обязателен")
        @Pattern(
                regexp = "^\\+[1-9]\\d{7,14}$",
                message = "Контактный телефон должен быть указан в формате E.164")
        String contactPhone) {
}
