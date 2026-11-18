package ru.itmo.highload.catering.kitchen.adapters.in.rest;

import static ru.itmo.highload.common.error.ErrorResponses.response;

import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import ru.itmo.highload.common.dto.out.ApiError;

@RestControllerAdvice
@org.springframework.core.annotation.Order(Ordered.HIGHEST_PRECEDENCE)
public class ProjectionExceptionHandler {
    @ExceptionHandler(DataAccessException.class)
    ApiError persistence(DataAccessException error, ServerWebExchange exchange) {
        LoggerFactory.getLogger(getClass())
                .error("Projection failed, traceId={}", exchange.getAttribute("traceId"), error);
        return response(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PROJECTION_UNAVAILABLE",
                "Не удалось сохранить задачу кухни; повторите запрос",
                List.of(),
                exchange);
    }
}
