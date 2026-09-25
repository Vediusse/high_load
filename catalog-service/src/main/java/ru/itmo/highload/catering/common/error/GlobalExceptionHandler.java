package ru.itmo.highload.catering.common.error;

import java.util.Comparator;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException error, ServerWebExchange exchange) {
        return response(error.getStatus(), error.getCode(), error.getMessage(), error.getFieldErrors(), exchange);
    }
    @ExceptionHandler(WebExchangeBindException.class)
    ResponseEntity<ApiError> validation(WebExchangeBindException error, ServerWebExchange exchange) {
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Запрос содержит некорректные поля",
                error.getFieldErrors().stream().map(field -> new FieldErrorResponse(field.getField(), field.getDefaultMessage()))
                        .sorted(Comparator.comparing(FieldErrorResponse::field)).toList(), exchange);
    }
    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<ApiError> input(ServerWebInputException error, ServerWebExchange exchange) {
        boolean body = error.getMethodParameter() != null && error.getMethodParameter()
                .hasParameterAnnotation(org.springframework.web.bind.annotation.RequestBody.class);
        String name = error.getMethodParameter() == null ? "request" : error.getMethodParameter().getParameterName();
        return response(HttpStatus.BAD_REQUEST, body ? "MALFORMED_JSON" : "VALIDATION_FAILED",
                "Запрос имеет неверный формат", body ? List.of() : List.of(new FieldErrorResponse(name, "имеет неверный формат")), exchange);
    }
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> optimistic(OptimisticLockingFailureException error, ServerWebExchange exchange) {
        return response(HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", "Объект был изменён параллельно", List.of(), exchange);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException error, ServerWebExchange exchange) {
        return response(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT", "Изменение конфликтует с текущими данными", List.of(), exchange);
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<ApiError> routing(org.springframework.web.server.ResponseStatusException error, ServerWebExchange exchange) {
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        String code = status == HttpStatus.NOT_FOUND ? "RESOURCE_NOT_FOUND" : "HTTP_" + status.value();
        return response(status, code, status.getReasonPhrase(), List.of(), exchange);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception error, ServerWebExchange exchange) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Request failed, traceId={}", exchange.getAttribute("traceId"), error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка сервера", List.of(), exchange);
    }
    private ResponseEntity<ApiError> response(HttpStatus status, String code, String message,
                                             List<FieldErrorResponse> fields, ServerWebExchange exchange) {
        return ResponseEntity.status(status).body(new ApiError(code, message, fields, exchange.getAttribute("traceId")));
    }
}
