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
import reactor.core.publisher.Flux;
import ru.itmo.highload.catering.catalog.dto.*;
import ru.itmo.highload.catering.catalog.entity.Category;
import ru.itmo.highload.catering.catalog.entity.Dish;
import ru.itmo.highload.catering.catalog.repository.CategoryRepository;
import ru.itmo.highload.catering.catalog.repository.DishRepository;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.error.ApiException;

@Service
public class CatalogService {
    private final CategoryRepository categories;
    private final DishRepository dishes;
    private final TransactionalOperator transaction;

    public CatalogService(CategoryRepository categories, DishRepository dishes,
                          ReactiveTransactionManager manager) {
        this.categories = categories;
        this.dishes = dishes;
        this.transaction = TransactionalOperator.create(manager);
    }

    public Mono<CategoryResponse> createCategory(CreateCategoryRequest request) {
        return Mono.defer(() -> categories.insert(new Category(request.name())))
                .map(this::response).as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> categoryConflict());
    }

    public Mono<PageResponse<CategoryResponse>> listCategories(PageRequest page) {
        return categories.findPage(page.getOffset(), page.getPageSize() + 1).map(this::response).collectList().map(items -> new PageResponse<>(
                        items.subList(0, Math.min(items.size(), page.getPageSize())),
                        page.getPageNumber(), page.getPageSize(), items.size() > page.getPageSize()));
    }

    public Mono<CategoryResponse> updateCategory(UUID id, UpdateCategoryRequest request) {
        return requireCategory(id).flatMap(category -> {
            category.update(request.name());
            return categories.save(category);
        }).map(this::response).as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> categoryConflict());
    }

    public Mono<Void> deleteCategory(UUID id) {
        return requireCategory(id).flatMap(categories::delete).then().as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "CATEGORY_IN_USE", "Нельзя удалить категорию, назначенную блюдам"));
    }

    public Mono<DishResponse> createDish(CreateDishRequest request) {
        return requireCategories(request.categoryIds()).collect(Collectors.toCollection(LinkedHashSet::new)).flatMap(categories -> {
            Dish dish = new Dish(request.name(), request.description(), request.currentPrice(), categories);
            return dishes.save(dish).flatMap(saved -> saveCategories(saved).then(response(saved)));
        }).as(transaction::transactional);
    }

    public Mono<DishResponse> getDish(UUID id) {
        return requireDish(id).flatMap(this::response);
    }

    public Mono<DishResponse> updateDish(UUID id, UpdateDishRequest request) {
        return requireDish(id).flatMap(dish -> requireCategories(request.categoryIds()).collect(Collectors.toCollection(LinkedHashSet::new)).flatMap(categories -> {
            dish.update(request.name(), request.description(), request.currentPrice(), categories);
            return dishes.save(dish).flatMap(saved -> saveCategories(saved).then(response(saved)));
        })).as(transaction::transactional);
    }

    public Mono<Void> deactivateDish(UUID id) {
        return requireDish(id).flatMap(dish -> {
            if (!dish.isActive()) return Mono.just(dish);
            dish.deactivate();
            return dishes.save(dish);
        }).then().as(transaction::transactional);
    }

    public Mono<DishCursorPageResponse> listActiveDishes(UUID afterId, int limit, UUID categoryId) {
        Mono<Void> categoryCheck = categoryId == null ? Mono.empty() : requireCategory(categoryId).then();
        return categoryCheck.thenMany(dishes.findActivePage(afterId, limit + 1, categoryId))
                .collectList().flatMap(fetched -> {
                    boolean hasNext = fetched.size() > limit;
                    List<Dish> page = fetched.subList(0, Math.min(limit, fetched.size()));
                    if (page.isEmpty()) return Mono.just(new DishCursorPageResponse(List.of(), null, false));
                    return dishes.findCategoryLinks(page.stream().map(Dish::getId).toList())
                            .collect(() -> new HashMap<UUID, Set<UUID>>(), (map, link) ->
                                    map.computeIfAbsent(link.dishId(), key -> new LinkedHashSet<>()).add(link.categoryId()))
                            .map(categories -> {
                        List<DishResponse> items = page.stream().map(dish -> response(dish,
                                categories.getOrDefault(dish.getId(), Set.of()))).toList();
                        return new DishCursorPageResponse(items, hasNext ? page.getLast().getId() : null, hasNext);
                    });
                });
    }

    public Flux<DishSnapshot> snapshots(Set<UUID> ids) {
        if (ids.isEmpty()) return Flux.empty();
        return dishes.findAllById(ids).collectMap(Dish::getId).flatMapMany(found -> {
            // Validate the entire bounded batch before emitting any response elements.
            for (UUID id : ids) {
                Dish dish = found.get(id);
                if (dish == null) throw notFound("Блюдо", id);
                if (!dish.isActive()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "DISH_INACTIVE", "Нельзя использовать неактивное блюдо " + id + " в новом заказе");
            }
            return Flux.fromIterable(ids).map(found::get)
                    .map(dish -> new DishSnapshot(dish.getId(), dish.getName(), dish.getCurrentPrice(), dish.isActive()));
        });
    }

    private Mono<Void> saveCategories(Dish dish) {
        // The enclosing service transaction makes the dish update and link replacement atomic.
        return dishes.deleteCategoryLinks(dish.getId())
                .thenMany(Flux.fromIterable(dish.getCategories())
                        .concatMap(category -> dishes.addCategoryLink(dish.getId(), category.getId())))
                .then();
    }

    private Flux<Category> requireCategories(Set<UUID> ids) {
        if (ids.isEmpty()) return Flux.empty();
        return categories.findAllById(ids).collectMap(Category::getId).flatMapMany(found -> {
            for (UUID id : ids) {
                if (!found.containsKey(id)) throw notFound("Категория", id);
            }
            return Flux.fromIterable(ids).map(found::get);
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
        return dishes.findCategoryLinks(List.of(dish.getId()))
                .map(link -> link.categoryId()).collect(Collectors.toSet())
                .map(ids -> response(dish, ids));
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
