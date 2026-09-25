package ru.itmo.highload.catering.common.config;

import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import ru.itmo.highload.catering.common.error.ApiError;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
    @ApiResponse(
            responseCode = "400",
            description = "Запрос не прошёл синтаксическую или полевую валидацию",
            headers = @Header(name = "X-Trace-Id", description = "Идентификатор запроса"),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
            responseCode = "404",
            description = "Запрошенный ресурс не найден",
            headers = @Header(name = "X-Trace-Id", description = "Идентификатор запроса"),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
            responseCode = "409",
            description = "Конфликт состояния, уникальности или версии ресурса",
            headers = @Header(name = "X-Trace-Id", description = "Идентификатор запроса"),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
            responseCode = "422",
            description = "Запрос нарушает бизнес-инвариант",
            headers = @Header(name = "X-Trace-Id", description = "Идентификатор запроса"),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
            responseCode = "503",
            description = "Сервис временно недоступен или занят",
            headers = @Header(name = "X-Trace-Id", description = "Идентификатор запроса"),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
            responseCode = "500",
            description = "Непредвиденная внутренняя ошибка",
            headers = @Header(name = "X-Trace-Id", description = "Идентификатор запроса"),
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
})
public @interface StandardApiErrors {
}
