package ru.itmo.highload.catering.catalog.dto.out;

import java.math.BigDecimal;
import java.util.UUID;

public record ActiveDishData(UUID id, String name, BigDecimal currentPrice) {
}
