package ru.itmo.highload.catering.catalog.service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.itmo.highload.catering.catalog.dto.CategoryResponse;
import ru.itmo.highload.catering.catalog.dto.ActiveDishData;
import ru.itmo.highload.catering.catalog.dto.CreateCategoryRequest;
import ru.itmo.highload.catering.catalog.dto.CreateDishRequest;
import ru.itmo.highload.catering.catalog.dto.DishCursorPageResponse;
import ru.itmo.highload.catering.catalog.dto.DishResponse;
import ru.itmo.highload.catering.catalog.dto.UpdateCategoryRequest;
import ru.itmo.highload.catering.catalog.dto.UpdateDishRequest;
import ru.itmo.highload.catering.catalog.entity.Category;
import ru.itmo.highload.catering.catalog.entity.Dish;
import ru.itmo.highload.catering.catalog.repository.CategoryRepository;
import ru.itmo.highload.catering.catalog.repository.DishRepository;
import ru.itmo.highload.catering.common.dto.PageResponse;
import ru.itmo.highload.catering.common.error.ApiException;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CatalogService {

    private final DishRepository dishRepository;
    private final CategoryRepository categoryRepository;

    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        String name = request.name().trim();
        if (categoryRepository.existsByName(name)) {
            throw categoryNameConflict();
        }
        return toResponse(categoryRepository.saveAndFlush(new Category(name)));
    }

    public PageResponse<CategoryResponse> listCategories(PageRequest pageRequest) {
        Page<Category> page = categoryRepository.findAll(pageRequest);
        return new PageResponse<>(
                page.getContent().stream().map(this::toResponse).toList(),
                page.getNumber(),
                page.getSize(),
                page.hasNext());
    }

    @Transactional
    public CategoryResponse updateCategory(UUID id, UpdateCategoryRequest request) {
        Category category = requireCategory(id);
        String name = request.name().trim();
        if (categoryRepository.existsByNameAndIdNot(name, id)) {
            throw categoryNameConflict();
        }
        category.update(name);
        return toResponse(categoryRepository.saveAndFlush(category));
    }

    @Transactional
    public void deleteCategory(UUID id) {
        Category category = requireCategory(id);
        if (dishRepository.existsByCategoriesId(id)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "CATEGORY_IN_USE",
                    "Нельзя удалить категорию, назначенную блюдам");
        }
        categoryRepository.delete(category);
        categoryRepository.flush();
    }

    @Transactional
    public DishResponse createDish(CreateDishRequest request) {
        Dish dish = new Dish(
                request.name(),
                request.description(),
                request.currentPrice(),
                requireCategories(request.categoryIds()));
        return toResponse(dishRepository.saveAndFlush(dish));
    }

    public DishResponse getDish(UUID id) {
        return toResponse(requireDish(id));
    }

    @Transactional
    public DishResponse updateDish(UUID id, UpdateDishRequest request) {
        Dish dish = requireDish(id);
        dish.update(
                request.name(),
                request.description(),
                request.currentPrice(),
                requireCategories(request.categoryIds()));
        return toResponse(dishRepository.saveAndFlush(dish));
    }

    @Transactional
    public void deactivateDish(UUID id) {
        Dish dish = requireDish(id);
        dish.deactivate();
        dishRepository.flush();
    }

    public DishCursorPageResponse listActiveDishes(UUID afterId, int limit, UUID categoryId) {
        if (categoryId != null) {
            requireCategory(categoryId);
        }
        List<UUID> fetchedIds = dishRepository.findActiveIdsAfter(
                afterId,
                categoryId,
                PageRequest.of(0, limit + 1));
        boolean hasNext = fetchedIds.size() > limit;
        List<UUID> pageIds = hasNext ? fetchedIds.subList(0, limit) : fetchedIds;

        Map<UUID, Dish> dishesById = pageIds.isEmpty()
                ? Map.of()
                : dishRepository.findAllWithCategoriesByIdIn(pageIds).stream()
                        .collect(Collectors.toMap(Dish::getId, Function.identity()));
        List<DishResponse> items = pageIds.stream()
                .map(dishesById::get)
                .map(this::toResponse)
                .toList();
        UUID nextCursor = hasNext ? pageIds.getLast() : null;
        return new DishCursorPageResponse(items, nextCursor, hasNext);
    }

    public Map<UUID, ActiveDishData> getActiveDishPrices(Set<UUID> dishIds) {
        if (dishIds == null) {
            throw new IllegalArgumentException("Набор блюд обязателен");
        }
        if (dishIds.isEmpty()) {
            return Map.of();
        }
        List<Dish> dishes = dishRepository.findAllById(dishIds);
        Map<UUID, Dish> foundById = dishes.stream()
                .collect(Collectors.toMap(Dish::getId, Function.identity()));
        Map<UUID, ActiveDishData> result = new LinkedHashMap<>();
        for (UUID dishId : dishIds) {
            Dish dish = foundById.get(dishId);
            if (dish == null) {
                throw notFound("Блюдо", dishId);
            }
            if (!dish.isActive()) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "DISH_INACTIVE",
                        "Нельзя использовать неактивное блюдо " + dishId + " в новом заказе");
            }
            result.put(dishId, new ActiveDishData(dish.getId(), dish.getName(), dish.getCurrentPrice()));
        }
        return result;
    }

    private Dish requireDish(UUID id) {
        return dishRepository.findById(id)
                .orElseThrow(() -> notFound("Блюдо", id));
    }

    private Category requireCategory(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> notFound("Категория", id));
    }

    private Set<Category> requireCategories(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<Category> categories = categoryRepository.findAllById(ids);
        Map<UUID, Category> foundById = categories.stream()
                .collect(Collectors.toMap(Category::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        for (UUID id : ids) {
            if (!foundById.containsKey(id)) {
                throw notFound("Категория", id);
            }
        }
        return ids.stream()
                .map(foundById::get)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private ApiException notFound(String resource, UUID id) {
        return new ApiException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_FOUND",
                resource + " с идентификатором " + id + " не найдена");
    }

    private ApiException categoryNameConflict() {
        return new ApiException(
                HttpStatus.CONFLICT,
                "CATEGORY_NAME_CONFLICT",
                "Категория с таким названием уже существует");
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName());
    }

    private DishResponse toResponse(Dish dish) {
        Set<UUID> categoryIds = dish.getCategories().stream()
                .map(Category::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new DishResponse(
                dish.getId(),
                dish.getName(),
                dish.getDescription(),
                dish.getCurrentPrice(),
                dish.isActive(),
                categoryIds,
                dish.getVersion());
    }
}
