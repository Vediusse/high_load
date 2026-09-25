package ru.itmo.highload.catering.catalog.service;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.catalog.dto.*;
import ru.itmo.highload.catering.catalog.entity.Category;
import ru.itmo.highload.catering.catalog.entity.Dish;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.error.ApiException;
import static org.springframework.data.relational.core.query.Criteria.where;
import static org.springframework.data.relational.core.query.Query.query;

@Service
public class CatalogService {
    private final R2dbcEntityTemplate template;
    private final TransactionalOperator transaction;

    public CatalogService(R2dbcEntityTemplate template, ReactiveTransactionManager manager) {
        this.template = template;
        this.transaction = TransactionalOperator.create(manager);
    }

    public Mono<CategoryResponse> createCategory(CreateCategoryRequest request) {
        return Mono.defer(() -> template.insert(new Category(request.name())))
                .map(this::response).as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> categoryConflict());
    }

    public Mono<PageResponse<CategoryResponse>> listCategories(PageRequest page) {
        return template.select(Category.class).matching(query(where("id").isNotNull())
                        .sort(Sort.by("id")).offset(page.getOffset()).limit(page.getPageSize() + 1))
                .all().map(this::response).collectList().map(items -> new PageResponse<>(
                        items.subList(0, Math.min(items.size(), page.getPageSize())),
                        page.getPageNumber(), page.getPageSize(), items.size() > page.getPageSize()));
    }

    public Mono<CategoryResponse> updateCategory(UUID id, UpdateCategoryRequest request) {
        return requireCategory(id).flatMap(category -> {
            category.update(request.name());
            return template.update(category);
        }).map(this::response).as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> categoryConflict());
    }

    public Mono<Void> deleteCategory(UUID id) {
        return requireCategory(id).flatMap(template::delete).then().as(transaction::transactional)
                .onErrorMap(DataIntegrityViolationException.class, error -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY, "CATEGORY_IN_USE", "Нельзя удалить категорию, назначенную блюдам"));
    }

    public Mono<DishResponse> createDish(CreateDishRequest request) {
        return requireCategories(request.categoryIds()).flatMap(categories -> {
            Dish dish = new Dish(request.name(), request.description(), request.currentPrice(), categories);
            return template.insert(dish).flatMap(saved -> saveCategories(saved).then(response(saved)));
        }).as(transaction::transactional);
    }

    public Mono<DishResponse> getDish(UUID id) {
        return requireDish(id).flatMap(this::response);
    }

    public Mono<DishResponse> updateDish(UUID id, UpdateDishRequest request) {
        return requireDish(id).flatMap(dish -> requireCategories(request.categoryIds()).flatMap(categories -> {
            dish.update(request.name(), request.description(), request.currentPrice(), categories);
            return template.update(dish).flatMap(saved -> saveCategories(saved).then(response(saved)));
        })).as(transaction::transactional);
    }

    public Mono<Void> deactivateDish(UUID id) {
        return requireDish(id).flatMap(dish -> {
            if (!dish.isActive()) return Mono.just(dish);
            dish.deactivate();
            return template.update(dish);
        }).then().as(transaction::transactional);
    }

    public Mono<DishCursorPageResponse> listActiveDishes(UUID afterId, int limit, UUID categoryId) {
        Mono<Void> categoryCheck = categoryId == null ? Mono.empty() : requireCategory(categoryId).then();
        String sql = "SELECT d.* FROM dish d WHERE d.active = true"
                + (afterId == null ? "" : " AND d.id > :afterId")
                + (categoryId == null ? "" : " AND EXISTS (SELECT 1 FROM dish_category dc WHERE dc.dish_id = d.id AND dc.category_id = :categoryId)")
                + " ORDER BY d.id LIMIT :limit";
        var statement = template.getDatabaseClient().sql(sql).bind("limit", limit + 1);
        if (afterId != null) statement = statement.bind("afterId", afterId);
        if (categoryId != null) statement = statement.bind("categoryId", categoryId);
        return categoryCheck.thenMany(statement.map((row, metadata) -> template.getConverter().read(Dish.class, row, metadata)).all())
                .collectList().flatMap(fetched -> {
                    boolean hasNext = fetched.size() > limit;
                    List<Dish> page = fetched.subList(0, Math.min(limit, fetched.size()));
                    return categoryIds(page.stream().map(Dish::getId).toList()).map(categories -> {
                        List<DishResponse> items = page.stream().map(dish -> response(dish,
                                categories.getOrDefault(dish.getId(), Set.of()))).toList();
                        return new DishCursorPageResponse(items, hasNext ? page.getLast().getId() : null, hasNext);
                    });
                });
    }

    public Mono<List<DishSnapshot>> snapshots(Set<UUID> ids) {
        if (ids.isEmpty()) return Mono.just(List.of());
        return template.select(Dish.class).matching(query(where("id").in(ids))).all()
                .collectMap(Dish::getId).map(found -> ids.stream().map(id -> {
                    Dish dish = found.get(id);
                    if (dish == null) throw notFound("Блюдо", id);
                    if (!dish.isActive()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "DISH_INACTIVE", "Нельзя использовать неактивное блюдо " + id + " в новом заказе");
                    return new DishSnapshot(id, dish.getName(), dish.getCurrentPrice(), dish.isActive());
                }).toList());
    }

    private Mono<Void> saveCategories(Dish dish) {
        return template.getDatabaseClient().sql("DELETE FROM dish_category WHERE dish_id = :id")
                .bind("id", dish.getId()).fetch().rowsUpdated()
                .thenMany(Flux.fromIterable(dish.getCategories()).concatMap(category -> template.getDatabaseClient()
                        .sql("INSERT INTO dish_category (dish_id, category_id) VALUES (:dish, :category)")
                        .bind("dish", dish.getId()).bind("category", category.getId()).fetch().rowsUpdated()))
                .then();
    }

    private Mono<Set<Category>> requireCategories(Set<UUID> ids) {
        if (ids.isEmpty()) return Mono.just(Set.of());
        return template.select(Category.class).matching(query(where("id").in(ids))).all()
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
        return template.selectOne(query(where("id").is(id)), Dish.class)
                .switchIfEmpty(Mono.error(notFound("Блюдо", id)));
    }

    private Mono<Category> requireCategory(UUID id) {
        return template.selectOne(query(where("id").is(id)), Category.class)
                .switchIfEmpty(Mono.error(notFound("Категория", id)));
    }

    private Mono<Map<UUID, Set<UUID>>> categoryIds(List<UUID> ids) {
        if (ids.isEmpty()) return Mono.just(Map.of());
        return template.getDatabaseClient().sql("SELECT dish_id, category_id FROM dish_category WHERE dish_id IN (:ids)")
                .bind("ids", ids).map((row, metadata) -> Map.entry(row.get("dish_id", UUID.class), row.get("category_id", UUID.class)))
                .all().collect(() -> new HashMap<UUID, Set<UUID>>(), (map, pair) ->
                        map.computeIfAbsent(pair.getKey(), key -> new LinkedHashSet<>()).add(pair.getValue()));
    }

    private Mono<DishResponse> response(Dish dish) {
        return categoryIds(List.of(dish.getId())).map(ids -> response(dish, ids.getOrDefault(dish.getId(), Set.of())));
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
