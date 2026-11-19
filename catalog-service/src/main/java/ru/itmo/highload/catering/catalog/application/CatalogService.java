package ru.itmo.highload.catering.catalog.application;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.application.dto.in.CreateCategoryRequest;
import ru.itmo.highload.catering.catalog.application.dto.in.CreateDishRequest;
import ru.itmo.highload.catering.catalog.application.dto.in.UpdateCategoryRequest;
import ru.itmo.highload.catering.catalog.application.dto.in.UpdateDishRequest;
import ru.itmo.highload.catering.catalog.application.dto.out.CategoryResponse;
import ru.itmo.highload.catering.catalog.application.dto.out.DishCursorPageResponse;
import ru.itmo.highload.catering.catalog.application.dto.out.DishResponse;
import ru.itmo.highload.catering.catalog.application.dto.out.DishSnapshot;
import ru.itmo.highload.catering.catalog.application.ports.CategoryStore;
import ru.itmo.highload.catering.catalog.application.ports.DishStore;
import ru.itmo.highload.catering.catalog.application.ports.ReactiveUnitOfWork;
import ru.itmo.highload.catering.catalog.domain.CatalogFailure;
import ru.itmo.highload.catering.catalog.domain.DataConflict;
import ru.itmo.highload.catering.catalog.domain.model.Category;
import ru.itmo.highload.catering.catalog.domain.model.Dish;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.dto.out.PageResponse;

@RequiredArgsConstructor
public class CatalogService {
    private final CategoryStore categories;
    private final DishStore dishes;
    private final ReactiveUnitOfWork transaction;

    public Mono<CategoryResponse> createCategory(CreateCategoryRequest request) {
        return Mono.defer(() -> categories.insert(new Category(request.name())))
                .map(this::response)
                .as(transaction::transactional)
                .onErrorMap(DataConflict.class, error -> categoryConflict());
    }

    public Mono<PageResponse<CategoryResponse>> listCategories(PageQuery query) {
        int page = query.page();
        int size = query.size();
        return categories
                .findPage((long) page * size, size + 1)
                .map(this::response)
                .collectList()
                .map(items ->
                        new PageResponse<>(
                                items.subList(0, Math.min(items.size(), size)),
                                page,
                                size,
                                items.size() > size));
    }

    public Mono<CategoryResponse> updateCategory(UUID id, UpdateCategoryRequest request) {
        return requireCategory(id)
                .flatMap(category -> {
                    category.update(request.name());
                    return categories.save(category);
                })
                .map(this::response)
                .as(transaction::transactional)
                .onErrorMap(DataConflict.class, error -> categoryConflict());
    }

    public Mono<Void> deleteCategory(UUID id) {
        return requireCategory(id)
                .flatMap(categories::delete)
                .then()
                .as(transaction::transactional)
                .onErrorMap(
                        DataConflict.class,
                        error ->
                                new CatalogFailure(
                                        CatalogFailure.Kind.UNPROCESSABLE,
                                        "CATEGORY_IN_USE",
                                        "Нельзя удалить категорию, назначенную блюдам"));
    }

    public Mono<DishResponse> createDish(CreateDishRequest request) {
        return requireCategories(request.categoryIds())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .flatMap(categories -> {
                    Dish dish =
                            new Dish(
                                    request.name(),
                                    request.description(),
                                    request.currentPrice(),
                                    categories);
                    return dishes.save(dish)
                            .flatMap(saved -> saveCategories(saved).then(response(saved)));
                })
                .as(transaction::transactional);
    }

    public Mono<DishResponse> getDish(UUID id) {
        return requireDish(id).flatMap(this::response);
    }

    public Mono<DishResponse> updateDish(UUID id, UpdateDishRequest request) {
        return requireDish(id)
                .flatMap(dish ->
                        requireCategories(request.categoryIds())
                                .collect(Collectors.toCollection(LinkedHashSet::new))
                                .flatMap(categories -> {
                                    dish.update(
                                            request.name(),
                                            request.description(),
                                            request.currentPrice(),
                                            categories);
                                    return dishes.save(dish)
                                            .flatMap(saved ->
                                                    saveCategories(saved).then(response(saved)));
                                }))
                .as(transaction::transactional);
    }

    public Mono<Void> deactivateDish(UUID id) {
        return requireDish(id)
                .flatMap(dish -> {
                    if (!dish.isActive()) return Mono.just(dish);
                    dish.deactivate();
                    return dishes.save(dish);
                })
                .then()
                .as(transaction::transactional);
    }

    public Mono<DishCursorPageResponse> listActiveDishes(UUID afterId, int limit, UUID categoryId) {
        Mono<Void> categoryCheck =
                categoryId == null ? Mono.empty() : requireCategory(categoryId).then();
        return categoryCheck
                .thenMany(dishes.findActivePage(afterId, limit + 1, categoryId))
                .collectList()
                .flatMap(fetched -> {
                    boolean hasNext = fetched.size() > limit;
                    List<Dish> page = fetched.subList(0, Math.min(limit, fetched.size()));
                    if (page.isEmpty())
                        return Mono.just(new DishCursorPageResponse(List.of(), null, false));
                    return dishes.findCategoryLinks(page.stream().map(Dish::getId).toList())
                            .collect(
                                    () -> new HashMap<UUID, Set<UUID>>(),
                                    (map, link) ->
                                            map.computeIfAbsent(
                                                            link.dishId(),
                                                            key -> new LinkedHashSet<>())
                                                    .add(link.categoryId()))
                            .map(categories -> {
                                List<DishResponse> items =
                                        page.stream()
                                                .map(dish ->
                                                        response(
                                                                dish,
                                                                categories.getOrDefault(
                                                                        dish.getId(), Set.of())))
                                                .toList();
                                return new DishCursorPageResponse(
                                        items, hasNext ? page.getLast().getId() : null, hasNext);
                            });
                });
    }

    public Flux<DishSnapshot> snapshots(Set<UUID> ids) {
        if (ids.isEmpty()) return Flux.empty();
        return dishes.findAllById(ids)
                .collectMap(Dish::getId)
                .flatMapMany(found -> {
                    // Validate the entire bounded batch before emitting any response elements.
                    for (UUID id : ids) {
                        Dish dish = found.get(id);
                        if (dish == null) throw notFound("Блюдо", id);
                        if (!dish.isActive())
                            throw new CatalogFailure(
                                    CatalogFailure.Kind.UNPROCESSABLE,
                                    "DISH_INACTIVE",
                                    "Нельзя использовать неактивное блюдо "
                                            + id
                                            + " в новом заказе");
                    }
                    return Flux.fromIterable(ids)
                            .map(found::get)
                            .map(dish ->
                                    new DishSnapshot(
                                            dish.getId(),
                                            dish.getName(),
                                            dish.getCurrentPrice(),
                                            dish.isActive()));
                });
    }

    private Mono<Void> saveCategories(Dish dish) {
        // The enclosing service transaction makes the dish update and link replacement atomic.
        return dishes.deleteCategoryLinks(dish.getId())
                .thenMany(
                        Flux.fromIterable(dish.getCategories())
                                .concatMap(category ->
                                        dishes.addCategoryLink(dish.getId(), category.getId())))
                .then();
    }

    private Flux<Category> requireCategories(Set<UUID> ids) {
        if (ids.isEmpty()) return Flux.empty();
        return categories
                .findAllById(ids)
                .collectMap(Category::getId)
                .flatMapMany(found -> {
                    for (UUID id : ids) {
                        if (!found.containsKey(id)) throw notFound("Категория", id);
                    }
                    return Flux.fromIterable(ids).map(found::get);
                });
    }

    private Mono<Dish> requireDish(UUID id) {
        return dishes.findById(id).switchIfEmpty(Mono.error(notFound("Блюдо", id)));
    }

    private Mono<Category> requireCategory(UUID id) {
        return categories.findById(id).switchIfEmpty(Mono.error(notFound("Категория", id)));
    }

    private Mono<DishResponse> response(Dish dish) {
        return dishes.findCategoryLinks(List.of(dish.getId()))
                .map(link -> link.categoryId())
                .collect(Collectors.toSet())
                .map(ids -> response(dish, ids));
    }

    private DishResponse response(Dish dish, Set<UUID> categories) {
        return new DishResponse(
                dish.getId(),
                dish.getName(),
                dish.getDescription(),
                dish.getCurrentPrice(),
                dish.isActive(),
                categories,
                dish.getVersion());
    }

    private CategoryResponse response(Category category) {
        return new CategoryResponse(category.getId(), category.getName());
    }

    private CatalogFailure categoryConflict() {
        return new CatalogFailure(
                CatalogFailure.Kind.CONFLICT,
                "CATEGORY_NAME_CONFLICT",
                "Категория с таким названием уже существует");
    }

    private CatalogFailure notFound(String resource, UUID id) {
        return new CatalogFailure(
                CatalogFailure.Kind.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                resource + " с идентификатором " + id + " не найдена");
    }
}
