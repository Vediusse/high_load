package ru.itmo.highload.catering.catalog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import ru.itmo.highload.catering.catalog.dto.in.CreateDishRequest;
import ru.itmo.highload.catering.catalog.dto.in.UpdateDishRequest;
import ru.itmo.highload.catering.catalog.dto.out.DishCursorPageResponse;
import ru.itmo.highload.catering.catalog.dto.out.DishResponse;
import ru.itmo.highload.catering.catalog.service.CatalogService;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.web.Pagination;

@RestController
@RequestMapping("/api/v1/dishes")
@Tag(name = "Каталог")
@StandardApiErrors
@RequiredArgsConstructor
public class DishController {

    private final CatalogService catalogService;

    @PostMapping
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    @Operation(summary = "Создать блюдо")
    @ApiResponse(responseCode = "201", description = "Блюдо создано")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<DishResponse> create(@Valid @RequestBody CreateDishRequest request, ServerHttpResponse httpResponse) {
        return catalogService.createDish(request).doOnNext(response ->
                httpResponse.getHeaders().setLocation(URI.create("/api/v1/dishes/" + response.id())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER', 'KITCHEN_MANAGER')")
    @Operation(summary = "Получить блюдо")
    public Mono<DishResponse> get(@PathVariable UUID id) {
        return catalogService.getDish(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    @Operation(summary = "Изменить блюдо")
    public Mono<DishResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDishRequest request) {
        return catalogService.updateDish(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    @Operation(summary = "Деактивировать блюдо")
    @ApiResponse(responseCode = "204", description = "Блюдо деактивировано")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deactivate(@PathVariable UUID id) {
        return catalogService.deactivateDish(id);
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ORGANIZATION_REPRESENTATIVE', 'CLIENT_MANAGER', 'KITCHEN_MANAGER')")
    @Operation(summary = "Получить активное меню cursor-страницей без total")
    public Mono<DishCursorPageResponse> list(
            @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) UUID categoryId) {
        return catalogService.listActiveDishes(afterId, Pagination.requireLimit(limit), categoryId);
    }
}
