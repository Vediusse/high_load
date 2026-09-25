package ru.itmo.highload.catering.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ActiveDishData(UUID id, String name, BigDecimal currentPrice) {
}
