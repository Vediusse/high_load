package ru.itmo.highload.common.error;

import static ru.itmo.highload.common.error.ErrorResponses.response;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import ru.itmo.highload.common.dto.out.ApiError;

@RestControllerAdvice
@org.springframework.core.annotation.Order(Ordered.LOWEST_PRECEDENCE - 10)
public class PersistenceExceptionHandler {
    @ExceptionHandler({
        DataAccessResourceFailureException.class,
        QueryTimeoutException.class,
        CannotCreateTransactionException.class,
        TransactionTimedOutException.class
    })
    ApiError unavailable(RuntimeException error, ServerWebExchange exchange) {
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE",
                "Хранилище временно недоступно",
                List.of(),
                exchange);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ApiError optimistic(OptimisticLockingFailureException error, ServerWebExchange exchange) {
        return response(
                HttpStatus.CONFLICT,
                "RESOURCE_VERSION_CONFLICT",
                "Объект был изменён параллельно",
                List.of(),
                exchange);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ApiError integrity(DataIntegrityViolationException error, ServerWebExchange exchange) {
        return response(
                HttpStatus.CONFLICT,
                "DATA_INTEGRITY_CONFLICT",
                "Изменение конфликтует с текущими данными",
                List.of(),
                exchange);
    }
}
