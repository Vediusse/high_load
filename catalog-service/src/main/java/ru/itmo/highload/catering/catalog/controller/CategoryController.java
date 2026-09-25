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
import ru.itmo.highload.catering.catalog.dto.CategoryResponse;
import ru.itmo.highload.catering.catalog.dto.CreateCategoryRequest;
import ru.itmo.highload.catering.catalog.dto.UpdateCategoryRequest;
import ru.itmo.highload.catering.catalog.service.CatalogService;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.web.Pagination;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Каталог")
@StandardApiErrors
@RequiredArgsConstructor
public class CategoryController {

    private final CatalogService catalogService;

    @PostMapping
    @Operation(summary = "Создать категорию")
    @ApiResponse(responseCode = "201", description = "Категория создана")
    public Mono<ResponseEntity<CategoryResponse>> create(@Valid @RequestBody CreateCategoryRequest request) {
        return catalogService.createCategory(request).map(response ->
                ResponseEntity.created(URI.create("/api/v1/categories/" + response.id())).body(response));
    }

    @GetMapping
    @Operation(summary = "Получить страницу категорий")
    public Mono<ResponseEntity<PageResponse<CategoryResponse>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalogService.listCategories(Pagination.pageRequest(page, size)).map(ResponseEntity::ok);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Изменить категорию")
    public Mono<ResponseEntity<CategoryResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryRequest request) {
        return catalogService.updateCategory(id, request).map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Удалить неиспользуемую категорию")
    @ApiResponse(responseCode = "204", description = "Категория удалена")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID id) {
        return catalogService.deleteCategory(id).thenReturn(ResponseEntity.noContent().build());
    }
}
