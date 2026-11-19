package ru.itmo.highload.catering.identity.adapters.in.rest;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.identity.application.ports.InternalTokens;
import ru.itmo.highload.common.dto.out.InternalTokenResponse;
import ru.itmo.highload.common.web.BlockingRequests;

@Hidden
@RestController
@RequestMapping("/internal/v1/auth")
@RequiredArgsConstructor
public class TokenExchangeController {
    private final InternalTokens tokens;
    private final BlockingRequests blocking;

    @PostMapping("/exchange")
    public Mono<InternalTokenResponse> exchange(@AuthenticationPrincipal Jwt actor) {
        if (!"HS256".equals(actor.getHeaders().get("alg"))) {
            throw new BadCredentialsException("Invalid token type");
        }
        return blocking.call(
                () -> tokens.issue(IdentityActors.claims(actor), actor.getExpiresAt()));
    }
}
