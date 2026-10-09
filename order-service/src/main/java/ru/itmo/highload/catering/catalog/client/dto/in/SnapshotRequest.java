package ru.itmo.highload.catering.catalog.client.dto.in;

import java.util.Set;
import java.util.UUID;

public record SnapshotRequest(Set<UUID> ids) {
}
