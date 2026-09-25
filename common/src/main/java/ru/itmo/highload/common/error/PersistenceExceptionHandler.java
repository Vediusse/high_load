package ru.itmo.highload.common.error;

import static ru.itmo.highload.common.error.ErrorResponses.response;

import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 10)
public class PersistenceExceptionHandler {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> optimistic(OptimisticLockingFailureException error, ServerWebExchange exchange) {
        return response(HttpStatus.CONFLICT, "RESOURCE_VERSION_CONFLICT", "Объект был изменён параллельно", List.of(), exchange);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException error, ServerWebExchange exchange) {
        return response(HttpStatus.CONFLICT, "DATA_INTEGRITY_CONFLICT", "Изменение конфликтует с текущими данными", List.of(), exchange);
    }
}
