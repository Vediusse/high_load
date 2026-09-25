package ru.itmo.highload.catering.catalog.dto;

import java.util.List;
import java.util.UUID;

public record DishCursorPageResponse(List<DishResponse> items, UUID nextCursor, boolean hasNext) {
}
