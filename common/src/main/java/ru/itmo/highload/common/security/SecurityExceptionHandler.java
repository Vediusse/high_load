package ru.itmo.highload.common.security;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
@Order(-10)
public class SecurityExceptionHandler {
    private final SecurityResponses responses;
    public SecurityExceptionHandler(SecurityResponses responses) { this.responses = responses; }

    @ExceptionHandler(AccessDeniedException.class)
    Mono<Void> denied(AccessDeniedException error, ServerWebExchange exchange) {
        return responses.write(exchange, HttpStatus.FORBIDDEN);
    }
}
