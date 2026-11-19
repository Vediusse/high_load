package ru.itmo.highload.catering.catalog.domain.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dish {

    private UUID id;

    private String name;

    private String description;

    private BigDecimal currentPrice;

    private boolean active;

    private Long version;

    private Set<Category> categories = new LinkedHashSet<>();

    public Dish(
            String name, String description, BigDecimal currentPrice, Set<Category> categories) {
        this.id = UUID.randomUUID();
        this.active = true;
        update(name, description, currentPrice, categories);
    }

    public void update(
            String name, String description, BigDecimal currentPrice, Set<Category> categories) {
        if (name == null || name.isBlank() || name.trim().length() > 200) {
            throw new IllegalArgumentException(
                    "Название блюда не должно быть пустым и длиннее 200 символов");
        }
        if (description == null || description.length() > 2000) {
            throw new IllegalArgumentException(
                    "Описание блюда обязательно и не должно быть длиннее 2000 символов");
        }
        if (currentPrice == null || currentPrice.signum() <= 0 || currentPrice.scale() > 2) {
            throw new IllegalArgumentException(
                    "Цена блюда должна быть положительной и содержать не более двух знаков после запятой");
        }
        if (categories == null || categories.stream().anyMatch(category -> category == null)) {
            throw new IllegalArgumentException(
                    "Набор категорий не может быть null или содержать null");
        }
        this.name = name.trim();
        this.description = description;
        this.currentPrice = currentPrice;
        this.categories.clear();
        this.categories.addAll(categories);
    }

    public void deactivate() {
        this.active = false;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }

    public Set<Category> getCategories() {
        return Collections.unmodifiableSet(categories);
    }

    public Long getRevision() {
        return version;
    }

    public static Dish restore(
            UUID id,
            String name,
            String description,
            BigDecimal price,
            boolean active,
            Long version,
            Set<Category> categories) {
        Dish dish = new Dish();
        dish.id = id;
        dish.name = name;
        dish.description = description;
        dish.currentPrice = price;
        dish.active = active;
        dish.version = version;
        dish.categories.addAll(categories);
        return dish;
    }
}
