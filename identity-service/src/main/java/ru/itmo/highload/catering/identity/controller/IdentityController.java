package ru.itmo.highload.catering.identity.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.identity.dto.in.CreateUserRequest;
import ru.itmo.highload.catering.identity.dto.in.UpdateUserRolesRequest;
import ru.itmo.highload.catering.identity.dto.out.CurrentUserResponse;
import ru.itmo.highload.catering.identity.dto.out.UserResponse;
import ru.itmo.highload.catering.identity.service.UserService;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.BlockingRequests;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Пользователи")
@StandardApiErrors
@ApiResponses({
    @ApiResponse(
            responseCode = "401",
            description = "Требуется действительный токен",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
    @ApiResponse(
            responseCode = "403",
            description = "Недостаточно прав",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))
})
@RequiredArgsConstructor
public class IdentityController {
    private final UserService users;
    private final BlockingRequests blocking;

    @PostMapping("/users")
    @Operation(summary = "Создать пользователя")
    @ApiResponse(responseCode = "201", description = "Пользователь создан")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<UserResponse> create(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal Jwt actor,
            ServerHttpResponse response) {
        return blocking.call(() -> users.create(request, actor))
                .doOnNext(
                        user ->
                                response.getHeaders()
                                        .setLocation(URI.create("/api/v1/users/" + user.id())));
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "Получить пользователя")
    public Mono<UserResponse> get(@PathVariable UUID id) {
        return blocking.call(() -> users.get(id));
    }

    @GetMapping("/users")
    @Operation(summary = "Получить страницу пользователей")
    public Mono<PageResponse<UserResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return blocking.call(() -> users.list(page, size));
    }

    @PutMapping("/users/{id}/roles")
    @Operation(summary = "Назначить роли пользователю")
    public Mono<UserResponse> roles(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRolesRequest request,
            @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> users.assignRoles(id, request, actor));
    }

    @PostMapping("/users/{id}/block")
    @Operation(summary = "Заблокировать пользователя")
    public Mono<UserResponse> block(@PathVariable UUID id) {
        return blocking.call(() -> users.block(id));
    }

    @GetMapping("/me")
    @Operation(summary = "Получить текущего пользователя")
    public Mono<CurrentUserResponse> me(@AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> users.current(actor));
    }

}
