package ru.itmo.highload.catering.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import ru.itmo.highload.common.security.InternalJwt;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.transaction.TransactionException;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.identity.security.JwtTokenService;
import ru.itmo.highload.catering.identity.security.SecurityErrors;
import ru.itmo.highload.catering.identity.service.UserService;
import ru.itmo.highload.common.web.BlockingRequests;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, ExchangeProperties.class})
public class SecurityConfiguration {
    @Bean
    @Order(-1)
    SecurityWebFilterChain exchangeSecurity(ServerHttpSecurity http, JwtTokenService tokens,
            UserService users, BlockingRequests blocking, SecurityErrors errors, ExchangeProperties properties) {
        return http.securityMatcher(ServerWebExchangeMatchers
                        .pathMatchers("/internal/v1/auth/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .addFilterBefore((exchange, chain) -> {
                    if (exchange.getRequest().getPath().value().equals("/internal/v1/auth/exchange")) {
                        var values = exchange.getRequest().getHeaders().getOrEmpty("X-Gateway-Credential");
                        if (values.size() != 1 || !MessageDigest.isEqual(
                                properties.gatewaySecret().getBytes(StandardCharsets.UTF_8),
                                values.getFirst().getBytes(StandardCharsets.UTF_8)))
                            return errors.write(exchange, HttpStatus.FORBIDDEN);
                    }
                    return chain.filter(exchange);
                }, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(access -> access.anyExchange().authenticated())
                .exceptionHandling(config -> config.authenticationEntryPoint((exchange, error) ->
                        errors.write(exchange, HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(resource -> resource.bearerTokenConverter(bearerTokenConverter())
                        .authenticationEntryPoint((exchange, error) -> errors.write(exchange,
                                error instanceof OAuth2AuthenticationException oauth
                                        && "temporarily_unavailable".equals(oauth.getError().getErrorCode())
                                        ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED))
                        .jwt(jwt -> jwt.jwtDecoder(tokens.decoder())
                                .jwtAuthenticationConverter(authenticationConverter(users, blocking))))
                .build();
    }
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    ApplicationRunner bootstrap(UserService users, Environment env) {
        return args -> users.bootstrap(
                env.getProperty("identity.bootstrap.login"),
                env.getProperty("identity.bootstrap.password"));
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, JwtTokenService tokens,
                                   UserService users, BlockingRequests blocking, SecurityErrors errors,
                                   ReactiveJwtDecoder internalJwtDecoder) {
        ServerAuthenticationEntryPoint entry = (exchange, error) -> {
            boolean unavailable = error instanceof OAuth2AuthenticationException oauth
                    && "temporarily_unavailable".equals(oauth.getError().getErrorCode());
            return errors.write(exchange, unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        };

        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(access -> access
                        .pathMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .pathMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll()
                        .pathMatchers("/api/v1/users", "/api/v1/users/**", "/actuator/info").hasRole("SUPERVISOR")
                        .pathMatchers(HttpMethod.GET, "/api/v1/me",
                                "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").authenticated()
                        // Unsupported methods on known paths reach WebFlux's standard 405 after authentication.
                        .pathMatchers("/api/v1/auth/login", "/api/v1/me").authenticated()
                        .anyExchange().denyAll())
                .exceptionHandling(config -> config.authenticationEntryPoint(entry)
                        .accessDeniedHandler((exchange, error) -> errors.denied(exchange)))
                .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(entry)
                        .bearerTokenConverter(bearerTokenConverter())
                        .accessDeniedHandler((exchange, error) -> errors.denied(exchange))
                        .jwt(jwt -> jwt.jwtDecoder(token -> internalJwtDecoder.decode(token)
                                        .onErrorResume(JwtException.class,
                                                error -> tokens.decoder().decode(token)))
                                .jwtAuthenticationConverter(authenticationConverter(users, blocking))))
                .build();
    }

    private Converter<Jwt, Mono<AbstractAuthenticationToken>> authenticationConverter(
            UserService users, BlockingRequests blocking) {
        return token -> blocking.<AbstractAuthenticationToken>call(() -> {
            if (!("RS256".equals(token.getHeaders().get("alg"))
                    && InternalJwt.TYPE.equals(token.getHeaders().get("typ")))) {
                users.validateCurrent(token);
            }
            var authorities = JwtTokenService.roles(token).stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                    .toList();
            return new JwtAuthenticationToken(token, authorities);
        }).onErrorMap(error -> error instanceof DataAccessException
                        || error instanceof TransactionException || error instanceof RejectedExecutionException,
                error -> new OAuth2AuthenticationException(
                        new OAuth2Error("temporarily_unavailable"), "Identity unavailable"));
    }

    private ServerAuthenticationConverter bearerTokenConverter() {
        var delegate = new ServerBearerTokenAuthenticationConverter();
        return exchange -> {
            if (exchange.getRequest().getHeaders().getOrEmpty(HttpHeaders.AUTHORIZATION).size() > 1) {
                return Mono.error(new OAuth2AuthenticationException("invalid_request"));
            }
            return delegate.convert(exchange);
        };
    }
}
