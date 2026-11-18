package ru.itmo.highload.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.security.InternalJwt;
import ru.itmo.highload.common.security.InternalJwtProperties;
import ru.itmo.highload.common.security.SecurityExceptionHandler;
import ru.itmo.highload.common.security.SecurityResponses;

@AutoConfiguration(
        before = ReactiveSecurityAutoConfiguration.class,
        beforeName = {
            "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive.ReactiveOAuth2ResourceServerAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.security.reactive.ReactiveManagementWebSecurityAutoConfiguration"
        })
@ConditionalOnClass(SecurityWebFilterChain.class)
@ConditionalOnProperty(
        prefix = "catering.security.internal",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(InternalJwtProperties.class)
@org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity
public class CommonSecurityAutoConfiguration {
    @Bean
    SecurityResponses securityResponses(ObjectMapper mapper) {
        return new SecurityResponses(mapper);
    }

    @Bean
    SecurityExceptionHandler securityExceptionHandler(SecurityResponses responses) {
        return new SecurityExceptionHandler(responses);
    }

    @Bean
    @ConditionalOnMissingBean(ReactiveJwtDecoder.class)
    ReactiveJwtDecoder internalJwtDecoder(
            InternalJwtProperties properties, Environment environment) {
        return InternalJwt.decoder(
                properties, environment.getRequiredProperty("spring.application.name"));
    }

    @Bean
    @ConditionalOnMissingBean(SecurityWebFilterChain.class)
    SecurityWebFilterChain internalSecurity(
            ServerHttpSecurity http, ReactiveJwtDecoder decoder, SecurityResponses errors) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .authorizeExchange(access ->
                        access.pathMatchers(
                                        HttpMethod.GET,
                                        "/v3/api-docs",
                                        "/v3/api-docs/**",
                                        "/swagger-ui.html",
                                        "/swagger-ui/**")
                                .permitAll()
                                .pathMatchers(
                                        HttpMethod.GET,
                                        "/actuator/health",
                                        "/actuator/health/liveness",
                                        "/actuator/health/readiness")
                                .permitAll()
                                .pathMatchers("/actuator/info")
                                .hasRole("SUPERVISOR")
                                .anyExchange()
                                .authenticated())
                .exceptionHandling(config ->
                        config.authenticationEntryPoint((exchange, error) ->
                                        errors.write(exchange, HttpStatus.UNAUTHORIZED))
                                .accessDeniedHandler((exchange, error) ->
                                        errors.write(exchange, HttpStatus.FORBIDDEN)))
                .oauth2ResourceServer(resource ->
                        resource.bearerTokenConverter(SecurityResponses.bearerConverter())
                                .authenticationEntryPoint((exchange, error) ->
                                        errors.write(exchange, HttpStatus.UNAUTHORIZED))
                                .accessDeniedHandler((exchange, error) ->
                                        errors.write(exchange, HttpStatus.FORBIDDEN))
                                .jwt(jwt ->
                                        jwt.jwtDecoder(decoder)
                                                .jwtAuthenticationConverter(token ->
                                                        Mono.just(
                                                                InternalJwt.authentication(
                                                                        token)))))
                .build();
    }
}
