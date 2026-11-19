package ru.itmo.highload.catering.catalog.adapters.out.persistence;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;
import ru.itmo.highload.catering.catalog.domain.model.Category;
import ru.itmo.highload.catering.catalog.domain.model.Dish;

@Table("dish")
public record DishRow(
        @Id UUID id,
        String name,
        String description,
        BigDecimal currentPrice,
        boolean active,
        @Version Long version) {
    public static DishRow from(Dish value) {
        return new DishRow(
                value.getId(),
                value.getName(),
                value.getDescription(),
                value.getCurrentPrice(),
                value.isActive(),
                value.getRevision());
    }

    public Dish toDomain() {
        return toDomain(Set.of());
    }

    public Dish toDomain(Set<Category> categories) {
        return Dish.restore(id, name, description, currentPrice, active, version, categories);
    }
}
