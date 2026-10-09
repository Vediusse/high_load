package ru.itmo.highload.common.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Ошибка отдельного поля запроса")
public record FieldErrorResponse(String field, String message) {
}
