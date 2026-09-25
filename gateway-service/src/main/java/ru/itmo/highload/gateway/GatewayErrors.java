package ru.itmo.highload.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import ru.itmo.highload.common.error.ApiError;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayErrors implements ErrorWebExceptionHandler {
    private final ObjectMapper mapper;

    public GatewayErrors(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }
        boolean missing = error instanceof ResponseStatusException status
                && status.getStatusCode().value() == 404;
        exchange.getResponse().setStatusCode(missing ? HttpStatus.NOT_FOUND : HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ApiError body = new ApiError(
                missing ? "RESOURCE_NOT_FOUND" : "DEPENDENCY_UNAVAILABLE",
                missing ? "Ресурс не найден" : "Сервис временно недоступен",
                List.of(), exchange.getAttributeOrDefault("traceId", "unknown"));
        try {
            return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory()
                    .wrap(mapper.writeValueAsBytes(body))));
        } catch (JsonProcessingException exception) {
            return Mono.error(exception);
        }
    }
}
