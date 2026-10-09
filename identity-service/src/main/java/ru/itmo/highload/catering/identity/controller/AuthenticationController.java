package ru.itmo.highload.catering.identity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.identity.dto.in.LoginRequest;
import ru.itmo.highload.catering.identity.dto.out.TokenResponse;
import ru.itmo.highload.catering.identity.service.UserService;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.web.BlockingRequests;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Аутентификация")
@StandardApiErrors
@RequiredArgsConstructor
public class AuthenticationController {
    private final UserService users;
    private final BlockingRequests blocking;

    @PostMapping("/login")
    @Operation(summary = "Войти и получить JWT")
    @SecurityRequirements
    @ApiResponse(responseCode = "401", description = "Неверные учётные данные",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public Mono<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return blocking.call(() -> users.login(request));
    }
}
