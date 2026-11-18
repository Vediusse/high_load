package ru.itmo.highload.common.error;

import static ru.itmo.highload.common.error.ErrorResponses.response;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import ru.itmo.highload.common.domain.BusinessFailure;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.dto.out.FieldErrorResponse;

@RestControllerAdvice
@org.springframework.core.annotation.Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessFailure.class)
    ApiError business(BusinessFailure error, ServerWebExchange exchange) {
        HttpStatus status =
                switch (error.kind()) {
                    case VALIDATION -> HttpStatus.BAD_REQUEST;
                    case UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED;
                    case FORBIDDEN -> HttpStatus.FORBIDDEN;
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case CONFLICT -> HttpStatus.CONFLICT;
                    case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                };
        return response(status, error.code(), error.getMessage(), List.of(), exchange);
    }

    @ExceptionHandler(RejectedExecutionException.class)
    ApiError overloaded(RejectedExecutionException error, ServerWebExchange exchange) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_BUSY",
                "Сервис занят, повторите запрос позже",
                List.of(),
                exchange);
    }

    @ExceptionHandler(ApiException.class)
    ApiError api(ApiException error, ServerWebExchange exchange) {
        return response(
                error.getStatus(),
                error.getCode(),
                error.getMessage(),
                error.getFieldErrors(),
                exchange);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ApiError validation(WebExchangeBindException error, ServerWebExchange exchange) {
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Запрос содержит некорректные поля",
                error.getFieldErrors().stream()
                        .map(field ->
                                new FieldErrorResponse(field.getField(), field.getDefaultMessage()))
                        .sorted(Comparator.comparing(FieldErrorResponse::field))
                        .toList(),
                exchange);
    }

    @ExceptionHandler(ServerWebInputException.class)
    ApiError input(ServerWebInputException error, ServerWebExchange exchange) {
        boolean body =
                error.getMethodParameter() != null
                        && error.getMethodParameter().hasParameterAnnotation(RequestBody.class);
        String name =
                error.getMethodParameter() == null
                        ? "request"
                        : error.getMethodParameter().getParameterName();
        return response(
                HttpStatus.BAD_REQUEST,
                body ? "MALFORMED_JSON" : "VALIDATION_FAILED",
                "Запрос имеет неверный формат",
                body ? List.of() : List.of(new FieldErrorResponse(name, "имеет неверный формат")),
                exchange);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ApiError routing(ResponseStatusException error, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        String code =
                status == HttpStatus.NOT_FOUND ? "RESOURCE_NOT_FOUND" : "HTTP_" + status.value();
        return response(status, code, status.getReasonPhrase(), List.of(), exchange);
    }

    @ExceptionHandler(Exception.class)
    ApiError unexpected(Exception error, ServerWebExchange exchange) {
        LoggerFactory.getLogger(getClass())
                .error("Request failed, traceId={}", exchange.getAttribute("traceId"), error);
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Внутренняя ошибка сервера",
                List.of(),
                exchange);
    }
}
