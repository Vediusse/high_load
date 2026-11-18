package ru.itmo.highload.catering.catalog.domain.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    private UUID id;


    private String name;

    public Category(String name) {
        this.id = UUID.randomUUID();
        update(name);
    }

    public void update(String name) {
        if (name == null || name.isBlank() || name.trim().length() > 100) {
            throw new IllegalArgumentException("Название категории не должно быть пустым и длиннее 100 символов");
        }
        this.name = name.trim();
    }

    public static Category restore(UUID id, String name) {
        Category category = new Category();
        category.id = id;
        category.name = name;
        return category;
    }
}
