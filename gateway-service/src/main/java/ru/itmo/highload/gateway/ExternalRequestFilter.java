package ru.itmo.highload.gateway;

import java.util.Locale;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.security.SecurityResponses;

@Component
@Order(-190)
public class ExternalRequestFilter implements WebFilter {
    private final SecurityResponses errors;

    public ExternalRequestFilter(SecurityResponses errors) { this.errors = errors; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/internal") || path.startsWith("/internal/"))
            return errors.write(exchange, HttpStatus.NOT_FOUND);
        return chain.filter(exchange.mutate().request(request -> request.headers(headers ->
                headers.keySet().removeIf(name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.startsWith("x-user-") || lower.startsWith("x-auth-")
                            || lower.equals("x-roles") || lower.equals("x-organization-id")
                            || lower.equals("x-gateway-credential");
                }))).build());
    }
}
