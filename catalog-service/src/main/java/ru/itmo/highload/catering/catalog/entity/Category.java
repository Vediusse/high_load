package ru.itmo.highload.catering.catalog.entity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("category")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    private UUID id;

    @NotBlank
    @Size(max = 100)
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
}
