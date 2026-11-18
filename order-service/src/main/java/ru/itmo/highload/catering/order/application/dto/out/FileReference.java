package ru.itmo.highload.catering.order.application.dto.out;

import java.util.UUID;

public record FileReference(UUID fileId, String relationType) {}
