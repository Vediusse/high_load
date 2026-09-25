package ru.itmo.highload.catering.catalog.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
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
    public ResponseEntity<DishResponse> create(@Valid @RequestBody CreateDishRequest request) {
        DishResponse response = catalogService.createDish(request);
        return ResponseEntity.created(URI.create("/api/v1/dishes/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить блюдо")
    public ResponseEntity<DishResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.getDish(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Изменить блюдо")
    public ResponseEntity<DishResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateDishRequest request) {
        return ResponseEntity.ok(catalogService.updateDish(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Деактивировать блюдо")
    @ApiResponse(responseCode = "204", description = "Блюдо деактивировано")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        catalogService.deactivateDish(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @Operation(summary = "Получить активное меню cursor-страницей без total")
    public ResponseEntity<DishCursorPageResponse> list(
            @RequestParam(required = false) UUID afterId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) UUID categoryId) {
        return ResponseEntity.ok(catalogService.listActiveDishes(afterId, Pagination.requireLimit(limit), categoryId));
    }
}
