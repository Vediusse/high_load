package ru.itmo.highload.catering.catalog.adapters.out.persistence;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import ru.itmo.highload.catering.catalog.domain.model.Category;

@Table("category")
public record CategoryRow(@Id UUID id, String name) {
    public static CategoryRow from(Category value) {
        return new CategoryRow(value.getId(), value.getName());
    }

    public Category toDomain() {
        return Category.restore(id, name);
    }
}
