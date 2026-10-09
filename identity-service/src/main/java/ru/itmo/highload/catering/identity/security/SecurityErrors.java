package ru.itmo.highload.catering.identity.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.error.ErrorResponses;

@Component
@RequiredArgsConstructor
public class SecurityErrors {
    private final ObjectMapper mapper;

    public Mono<Void> denied(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        // Only these existing endpoints have a role restriction; other denied paths do not exist.
        boolean protectedResource =
                path.equals("/api/v1/users")
                        || path.startsWith("/api/v1/users/")
                        || path.equals("/actuator/info");
        return write(exchange, protectedResource ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND);
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status) {
        String code =
                switch (status) {
                    case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
                    case FORBIDDEN -> "ACCESS_DENIED";
                    case NOT_FOUND -> "RESOURCE_NOT_FOUND";
                    default -> "DEPENDENCY_UNAVAILABLE";
                };
        String message =
                switch (status) {
                    case UNAUTHORIZED -> "Требуется действительный токен";
                    case FORBIDDEN -> "Недостаточно прав";
                    case NOT_FOUND -> "Ресурс не найден";
                    default -> "Проверка пользователя временно недоступна";
                };
        var body = ErrorResponses.response(status, code, message, List.of(), exchange);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED)
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        try {
            return exchange.getResponse()
                    .writeWith(
                            Mono.just(
                                    exchange.getResponse()
                                            .bufferFactory()
                                            .wrap(mapper.writeValueAsBytes(body))));
        } catch (JsonProcessingException impossible) {
            return Mono.error(impossible);
        }
    }
}
