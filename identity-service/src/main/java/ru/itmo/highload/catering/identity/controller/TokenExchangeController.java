package ru.itmo.highload.catering.identity.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.identity.security.InternalTokenService;
import ru.itmo.highload.common.dto.out.InternalTokenResponse;
import ru.itmo.highload.common.web.BlockingRequests;

@Hidden
@RestController
@RequestMapping("/internal/v1/auth")
@RequiredArgsConstructor
public class TokenExchangeController {
    private final InternalTokenService tokens;
    private final BlockingRequests blocking;

    @PostMapping("/exchange")
    public Mono<InternalTokenResponse> exchange(@AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> tokens.issue(actor));
    }
}
