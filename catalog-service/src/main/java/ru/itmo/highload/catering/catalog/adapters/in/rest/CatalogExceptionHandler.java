package ru.itmo.highload.catering.catalog.adapters.in.rest;

import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import ru.itmo.highload.catering.catalog.domain.CatalogFailure;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.error.ErrorResponses;

@Order(-10)
@RestControllerAdvice
public class CatalogExceptionHandler {
    @ExceptionHandler(CatalogFailure.class)
    public ApiError handle(CatalogFailure error, ServerWebExchange exchange) {
        HttpStatus status =
                switch (error.kind()) {
                    case NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case CONFLICT -> HttpStatus.CONFLICT;
                    case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
                };
        return ErrorResponses.response(
                status, error.code(), error.getMessage(), List.of(), exchange);
    }
}
