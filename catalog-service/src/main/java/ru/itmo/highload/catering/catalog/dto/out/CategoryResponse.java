package ru.itmo.highload.catering.catalog.dto.out;

import java.util.UUID;

public record CategoryResponse(UUID id, String name) {
}
