package ru.itmo.highload.catering.catalog.dto.in;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;

public record SnapshotRequest(@NotNull @Size(max = 1000) Set<@NotNull UUID> ids) {
}
