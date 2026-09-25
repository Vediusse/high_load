package ru.itmo.highload.catering.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrganizationRequest(
        @NotBlank(message = "Название организации обязательно")
        @Size(max = 200, message = "Название организации не должно быть длиннее 200 символов")
        String name,

        @NotBlank(message = "Телефон обязателен")
        @Pattern(
                regexp = "^\\+[1-9]\\d{7,14}$",
                message = "Телефон должен быть указан в формате E.164, например +79991234567")
        String phone) {
}
