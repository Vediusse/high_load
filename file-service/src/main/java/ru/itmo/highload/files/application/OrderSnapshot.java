package ru.itmo.highload.files.application;

import java.util.UUID;

public record OrderSnapshot(UUID id, UUID organizationId, String status, long version) {}
