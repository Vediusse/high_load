package ru.itmo.highload.catering.catalog.service;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.dto.*;
import ru.itmo.highload.catering.catalog.entity.Category;
import ru.itmo.highload.catering.catalog.entity.Dish;
import ru.itmo.highload.catering.catalog.repository.CategoryRepository;
import ru.itmo.highload.catering.catalog.repository.DishRepository;
import ru.itmo.highload.catering.catalog.repository.DishCategoryRepository;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.error.ApiException;

@Service
public class CatalogService {
    private final CategoryRepository categories;
    private final DishRepository dishes;
    private final DishCategoryRepository dishCategories;
    private final TransactionalOperator transaction;

    public CatalogService(CategoryRepository categories, DishRepository dishes,
                          DishCategoryRepository dishCategories, ReactiveTransactionManager manager) {
        this.categories = categories;
        this.dishes = dishes;
        this.dishCategories = dishCategories;
        this.transaction = TransactionalOperator.create(manager);
    }

    public Mono<CategoryResponse> createCategory(CreateCategoryRequest request) {
        return Mono.defer(() -> categories.insert(new Category(request.name())))
                .map(this::response).as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> categoryConflict());
    }

    public Mono<PageResponse<CategoryResponse>> listCategories(PageRequest page) {
        return categories.findPage(page).map(this::response).collectList().map(items -> new PageResponse<>(
                        items.subList(0, Math.min(items.size(), page.getPageSize())),
                        page.getPageNumber(), page.getPageSize(), items.size() > page.getPageSize()));
    }

    public Mono<CategoryResponse> updateCategory(UUID id, UpdateCategoryRequest request) {
        return requireCategory(id).flatMap(category -> {
            category.update(request.name());
            return categories.update(category);
        }).map(this::response).as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> categoryConflict());
    }

    public Mono<Void> deleteCategory(UUID id) {
        return requireCategory(id).flatMap(categories::delete).then().as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "CATEGORY_IN_USE", "Нельзя удалить категорию, назначенную блюдам"));
    }

    public Mono<DishResponse> createDish(CreateDishRequest request) {
        return requireCategories(request.categoryIds()).flatMap(categories -> {
            Dish dish = new Dish(request.name(), request.description(), request.currentPrice(), categories);
            return dishes.insert(dish).flatMap(saved -> saveCategories(saved).then(response(saved)));
        }).as(transaction::transactional);
    }

    public Mono<DishResponse> getDish(UUID id) {
        return requireDish(id).flatMap(this::response);
    }

    public Mono<DishResponse> updateDish(UUID id, UpdateDishRequest request) {
        return requireDish(id).flatMap(dish -> requireCategories(request.categoryIds()).flatMap(categories -> {
            dish.update(request.name(), request.description(), request.currentPrice(), categories);
            return dishes.update(dish).flatMap(saved -> saveCategories(saved).then(response(saved)));
        })).as(transaction::transactional);
    }

    public Mono<Void> deactivateDish(UUID id) {
        return requireDish(id).flatMap(dish -> {
            if (!dish.isActive()) return Mono.just(dish);
            dish.deactivate();
            return dishes.update(dish);
        }).then().as(transaction::transactional);
    }

    public Mono<DishCursorPageResponse> listActiveDishes(UUID afterId, int limit, UUID categoryId) {
        Mono<Void> categoryCheck = categoryId == null ? Mono.empty() : requireCategory(categoryId).then();
        return categoryCheck.thenMany(dishes.findActivePage(afterId, limit + 1, categoryId))
                .collectList().flatMap(fetched -> {
                    boolean hasNext = fetched.size() > limit;
                    List<Dish> page = fetched.subList(0, Math.min(limit, fetched.size()));
                    return dishCategories.findCategoryIdsByDishIds(page.stream().map(Dish::getId).toList()).map(categories -> {
                        List<DishResponse> items = page.stream().map(dish -> response(dish,
                                categories.getOrDefault(dish.getId(), Set.of()))).toList();
                        return new DishCursorPageResponse(items, hasNext ? page.getLast().getId() : null, hasNext);
                    });
                });
    }

    public Mono<List<DishSnapshot>> snapshots(Set<UUID> ids) {
        if (ids.isEmpty()) return Mono.just(List.of());
        return dishes.findAllById(ids)
                .collectMap(Dish::getId).map(found -> ids.stream().map(id -> {
                    Dish dish = found.get(id);
                    if (dish == null) throw notFound("Блюдо", id);
                    if (!dish.isActive()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "DISH_INACTIVE", "Нельзя использовать неактивное блюдо " + id + " в новом заказе");
                    return new DishSnapshot(id, dish.getName(), dish.getCurrentPrice(), dish.isActive());
                }).toList());
    }

    private Mono<Void> saveCategories(Dish dish) {
        return dishCategories.replace(dish.getId(), dish.getCategories().stream()
                .map(Category::getId).collect(Collectors.toSet()));
    }

    private Mono<Set<Category>> requireCategories(Set<UUID> ids) {
        if (ids.isEmpty()) return Mono.just(Set.of());
        return categories.findAllById(ids)
                .collectMap(Category::getId).map(found -> {
                    Set<Category> categories = new LinkedHashSet<>();
                    for (UUID id : ids) {
                        if (!found.containsKey(id)) throw notFound("Категория", id);
                        categories.add(found.get(id));
                    }
                    return categories;
                });
    }

    private Mono<Dish> requireDish(UUID id) {
        return dishes.findById(id)
                .switchIfEmpty(Mono.error(notFound("Блюдо", id)));
    }

    private Mono<Category> requireCategory(UUID id) {
        return categories.findById(id)
                .switchIfEmpty(Mono.error(notFound("Категория", id)));
    }

    private Mono<DishResponse> response(Dish dish) {
        return dishCategories.findCategoryIdsByDishIds(List.of(dish.getId())).map(ids -> response(dish, ids.getOrDefault(dish.getId(), Set.of())));
    }

    private DishResponse response(Dish dish, Set<UUID> categories) {
        return new DishResponse(dish.getId(), dish.getName(), dish.getDescription(), dish.getCurrentPrice(),
                dish.isActive(), categories, dish.getVersion());
    }

    private CategoryResponse response(Category category) { return new CategoryResponse(category.getId(), category.getName()); }
    private ApiException categoryConflict() {
        return new ApiException(HttpStatus.CONFLICT, "CATEGORY_NAME_CONFLICT", "Категория с таким названием уже существует");
    }
    private ApiException notFound(String resource, UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", resource + " с идентификатором " + id + " не найдена");
    }
}
