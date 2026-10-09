package ru.itmo.highload.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class InternalBearerFilter implements GlobalFilter, Ordered {
    @Override public int getOrder() { return -10; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal().ofType(JwtAuthenticationToken.class)
                .map(actor -> exchange.mutate().request(request -> request.headers(headers ->
                        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + actor.getToken().getTokenValue()))).build())
                .defaultIfEmpty(exchange).flatMap(chain::filter);
    }
}
