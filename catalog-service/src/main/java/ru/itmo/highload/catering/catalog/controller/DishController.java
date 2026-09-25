package ru.itmo.highload.catering.catalog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import reactor.core.publisher.Mono;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.itmo.highload.catering.catalog.dto.CreateDishRequest;
import ru.itmo.highload.catering.catalog.dto.DishCursorPageResponse;
import ru.itmo.highload.catering.catalog.dto.DishResponse;
import ru.itmo.highload.catering.catalog.dto.UpdateDishRequest;
import ru.itmo.highload.catering.catalog.service.CatalogService;
import ru.itmo.highload.catering.common.web.Pagination;
import ru.itmo.highload.catering.common.config.StandardApiErrors;

@RestController
@RequestMapping("/api/v1/dishes")
@Tag(name = "Каталог")
@StandardApiErrors
@RequiredArgsConstructor
public class DishController {

    private final CatalogService catalogService;

    @PostMapping
    @Operation(summary = "Создать блюдо")
    @ApiResponse(responseCode = "201", description = "Блюдо создано")
    public Mono<ResponseEntity<DishResponse>> create(@Valid @RequestBody CreateDishRequest request) {
        return catalogService.createDish(request).map(response ->
                ResponseEntity.created(URI.create("/api/v1/dishes/" + response.id())).body(response));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить блюдо")
    public Mono<ResponseEntity<DishResponse>> get(@PathVariable UUID id) {
        return catalogService.getDish(id).map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Изменить блюдо")
    public Mono<ResponseEntity<DishResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDishRequest request) {
        return catalogService.updateDish(id, request).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Деактивировать блюдо")
    @ApiResponse(responseCode = "204", description = "Блюдо деактивировано")
    public Mono<ResponseEntity<Void>> deactivate(@PathVariable UUID id) {
        return catalogService.deactivateDish(id).thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping
    @Operation(summary = "Получить активное меню cursor-страницей без total")
    public Mono<ResponseEntity<DishCursorPageResponse>> list(
            @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) UUID categoryId) {
        return catalogService.listActiveDishes(afterId, Pagination.requireLimit(limit), categoryId).map(ResponseEntity::ok);
    }
}
