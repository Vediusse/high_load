package ru.itmo.highload.catering.catalog.entity;

import java.util.UUID;
import org.springframework.data.relational.core.mapping.Table;

@Table("dish_category")
public record DishCategory(UUID dishId, UUID categoryId) {}
