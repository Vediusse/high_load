package ru.itmo.highload.catering.catalog.dto;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record DishResponse(
        UUID id,
        String name,
        String description,
        BigDecimal currentPrice,
        boolean active,
        Set<UUID> categoryIds,
        long version) {
}
