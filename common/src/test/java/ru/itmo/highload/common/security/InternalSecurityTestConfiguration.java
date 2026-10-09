package ru.itmo.highload.common.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

@TestConfiguration(proxyBeanMethods = false)
public class InternalSecurityTestConfiguration {
    @Bean
    WebTestClientBuilderCustomizer testBearer() {
        return builder -> builder.filter(ExchangeFilterFunction.ofRequestProcessor(request -> Mono.just(
                ClientRequest.from(request).headers(headers -> {
                    if (!headers.containsKey("Authorization")) headers.set("Authorization", TestTokens.bearer());
                }).build())));
    }
}
