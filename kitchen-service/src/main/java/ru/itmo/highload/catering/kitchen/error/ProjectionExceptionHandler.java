package ru.itmo.highload.catering.kitchen.error;

import static ru.itmo.highload.common.error.ErrorResponses.response;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import ru.itmo.highload.common.error.ApiError;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ProjectionExceptionHandler {
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<ApiError> persistence(org.springframework.dao.DataAccessException error, ServerWebExchange exchange) {
        org.slf4j.LoggerFactory.getLogger(getClass()).error("Projection failed, traceId={}", exchange.getAttribute("traceId"), error);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "PROJECTION_UNAVAILABLE",
                "Не удалось сохранить задачу кухни; повторите запрос", List.of(), exchange);
    }
}
