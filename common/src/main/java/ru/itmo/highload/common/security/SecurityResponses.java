package ru.itmo.highload.common.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.error.ErrorResponses;

public class SecurityResponses {
    private final ObjectMapper mapper;

    public SecurityResponses(ObjectMapper mapper) { this.mapper = mapper; }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatus status) {
        String code = switch (status) {
            case UNAUTHORIZED -> "AUTHENTICATION_REQUIRED";
            case FORBIDDEN -> "ACCESS_DENIED";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            default -> "DEPENDENCY_UNAVAILABLE";
        };
        String message = switch (status) {
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
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
                    .wrap(mapper.writeValueAsBytes(body))));
        } catch (JsonProcessingException error) { return Mono.error(error); }
    }

    public static ServerAuthenticationConverter bearerConverter() {
        var delegate = new ServerBearerTokenAuthenticationConverter();
        return exchange -> exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION).size() > 1
                ? Mono.error(new OAuth2AuthenticationException("invalid_request")) : delegate.convert(exchange);
    }
}
