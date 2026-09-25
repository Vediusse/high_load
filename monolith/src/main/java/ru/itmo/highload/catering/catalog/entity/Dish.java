package ru.itmo.highload.catering.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "dish")
public class Dish {

    @Id
    private UUID id;

    @NotBlank
    @Size(max = 200)
    @Column(nullable = false, length = 200)
    private String name;

    @NotNull
    @Size(max = 2000)
    @Column(nullable = false, length = 2000)
    private String description;

    @NotNull
    @DecimalMin(value = "0.00", inclusive = false)
    @Digits(integer = 17, fraction = 2)
    @Column(name = "current_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal currentPrice;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "dish_category",
            joinColumns = @JoinColumn(name = "dish_id"),
            inverseJoinColumns = @JoinColumn(name = "category_id"))
    private Set<Category> categories = new LinkedHashSet<>();

    protected Dish() {
    }

    public Dish(String name, String description, BigDecimal currentPrice, Set<Category> categories) {
        this.id = UUID.randomUUID();
        this.active = true;
        update(name, description, currentPrice, categories);
    }

    public void update(String name, String description, BigDecimal currentPrice, Set<Category> categories) {
        if (name == null || name.isBlank() || name.trim().length() > 200) {
            throw new IllegalArgumentException("Название блюда не должно быть пустым и длиннее 200 символов");
        }
        if (description == null || description.length() > 2000) {
            throw new IllegalArgumentException("Описание блюда обязательно и не должно быть длиннее 2000 символов");
        }
        if (currentPrice == null || currentPrice.signum() <= 0 || currentPrice.scale() > 2) {
            throw new IllegalArgumentException("Цена блюда должна быть положительной и содержать не более двух знаков после запятой");
        }
        if (categories == null || categories.stream().anyMatch(category -> category == null)) {
            throw new IllegalArgumentException("Набор категорий не может быть null или содержать null");
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

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public boolean isActive() {
        return active;
    }

    public long getVersion() {
        return version;
    }

    public Set<Category> getCategories() {
        return Collections.unmodifiableSet(categories);
    }
}
