package ru.itmo.highload.catering.catalog.application.dto.out;

import java.util.UUID;

public record CategoryResponse(UUID id, String name) {}
