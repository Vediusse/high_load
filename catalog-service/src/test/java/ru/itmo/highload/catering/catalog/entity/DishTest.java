package ru.itmo.highload.catering.catalog.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DishTest {

    @Test
    void createsActiveDishAndTrimsName() {
        Category category = new Category("Основные блюда");

        Dish dish = new Dish("  Котлета  ", "С гарниром", new BigDecimal("320.00"), Set.of(category));

        assertThat(dish.getId()).isNotNull();
        assertThat(dish.getName()).isEqualTo("Котлета");
        assertThat(dish.getCurrentPrice()).isEqualByComparingTo("320.00");
        assertThat(dish.isActive()).isTrue();
        assertThat(dish.getCategories()).containsExactly(category);
    }

    @Test
    void rejectsBlankNameAndInvalidPrices() {
        assertThatThrownBy(() -> new Dish(" ", "", BigDecimal.ONE, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Название блюда");
        assertThatThrownBy(() -> new Dish("Борщ", "", BigDecimal.ZERO, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Цена блюда");
        assertThatThrownBy(() -> new Dish("Борщ", "", new BigDecimal("1.001"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Цена блюда");
    }

    @Test
    void updateReplacesCategoriesAndDeactivateKeepsDish() {
        Category oldCategory = new Category("Супы");
        Category newCategory = new Category("Обеды");
        Dish dish = new Dish("Борщ", "", new BigDecimal("180.00"), Set.of(oldCategory));

        dish.update("Щи", "Постные", new BigDecimal("190.00"), Set.of(newCategory));
        dish.deactivate();

        assertThat(dish.getName()).isEqualTo("Щи");
        assertThat(dish.getDescription()).isEqualTo("Постные");
        assertThat(dish.getCategories()).containsExactly(newCategory);
        assertThat(dish.isActive()).isFalse();
    }
}
