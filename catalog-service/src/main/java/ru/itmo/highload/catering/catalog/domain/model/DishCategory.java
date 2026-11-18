package ru.itmo.highload.catering.catalog.domain.model;

import java.util.UUID;

public record DishCategory(UUID dishId, UUID categoryId) {}
