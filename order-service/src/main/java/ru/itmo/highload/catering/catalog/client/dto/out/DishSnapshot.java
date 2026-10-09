package ru.itmo.highload.catering.catalog.client.dto.out;

import java.math.BigDecimal;
import java.util.UUID;

public record DishSnapshot(UUID id, String name, BigDecimal price, boolean active) {
}
