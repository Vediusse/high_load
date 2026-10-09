package ru.itmo.highload.gateway;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import java.util.Base64;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import ru.itmo.highload.common.dto.out.InternalTokenResponse;
import ru.itmo.highload.common.security.InternalJwt;
import ru.itmo.highload.common.security.SecurityResponses;

@Configuration
public class GatewaySecurityConfiguration {
    @Bean
    @LoadBalanced
    WebClient.Builder identityWebClient() {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                .disableRetry(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000)));
    }

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http,
            @Qualifier("identityWebClient") WebClient.Builder builder,
            @Value("${identity.exchange.gateway-secret}") String gatewaySecret,
            ReactiveJwtDecoder decoder, SecurityResponses errors) {
        if (Base64.getDecoder().decode(gatewaySecret).length < 32)
            throw new IllegalArgumentException("Configure a random Gateway exchange credential");
        WebClient identity = builder.baseUrl("http://identity-service").build();
        ReactiveAuthenticationManager manager = credentials -> Mono.deferContextual(context ->
                identity.post().uri("/internal/v1/auth/exchange")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ((BearerTokenAuthenticationToken) credentials).getToken())
                        .header("X-Gateway-Credential", gatewaySecret)
                        .header("X-Trace-Id", context.getOrDefault("traceId", "gateway"))
                        .exchangeToMono(response -> {
                            if (response.statusCode().value() == 401)
                                return response.releaseBody().then(Mono.error(new BadCredentialsException("Invalid token")));
                            if (!response.statusCode().is2xxSuccessful())
                                return response.releaseBody().then(Mono.error(new IllegalStateException("Identity exchange unavailable")));
                            return response.bodyToMono(InternalTokenResponse.class)
                                    .filter(token -> "Bearer".equals(token.tokenType()) && token.accessToken() != null)
                                    .switchIfEmpty(Mono.error(new IllegalStateException("Invalid exchange response")))
                                    .flatMap(token -> decoder.decode(token.accessToken()))
                                    .map(jwt -> (Authentication) InternalJwt.authentication(jwt));
                        }).timeout(Duration.ofSeconds(2)))
                .onErrorMap(error -> !(error instanceof AuthenticationException),
                        error -> new OAuth2AuthenticationException(new OAuth2Error("temporarily_unavailable"),
                                "Identity unavailable"));
        var authentication = new AuthenticationWebFilter(manager);
        authentication.setServerAuthenticationConverter(SecurityResponses.bearerConverter());
        authentication.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        authentication.setAuthenticationFailureHandler((exchange, error) -> errors.write(exchange.getExchange(),
                error instanceof OAuth2AuthenticationException oauth
                        && "temporarily_unavailable".equals(oauth.getError().getErrorCode())
                        ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED));
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .addFilterAt(authentication, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(access -> access
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .pathMatchers("/actuator/info").hasRole("SUPERVISOR")
                        .anyExchange().authenticated())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint((exchange, error) -> errors.write(exchange, HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((exchange, error) -> errors.write(exchange, HttpStatus.FORBIDDEN)))
                .build();
    }
}
