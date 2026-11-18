package ru.itmo.highload.files.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import ru.itmo.highload.common.domain.BusinessFailure;

public record FileDocument(
        UUID id,
        UUID orderId,
        RelationType relationType,
        String originalName,
        String mimeType,
        long size,
        String checksum,
        UUID uploadedBy,
        Instant uploadedAt,
        Status status,
        UUID storageKey,
        long version) {
    public enum Status {
        ACTIVE,
        DELETED
    }

    public FileDocument {
        Objects.requireNonNull(id);
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(relationType);
        Objects.requireNonNull(uploadedBy);
        Objects.requireNonNull(uploadedAt);
        Objects.requireNonNull(status);
        Objects.requireNonNull(storageKey);
        validateName(originalName);
        if (size < 1
                || version < 0
                || checksum == null
                || !checksum.matches("[0-9a-f]{64}")
                || mimeType == null) throw new IllegalArgumentException("Invalid file metadata");
    }

    public static void validateName(String name) {
        if (name == null
                || name.isBlank()
                || name.length() > 255
                || name.chars().anyMatch(Character::isISOControl))
            throw new BusinessFailure(
                    BusinessFailure.Kind.VALIDATION, "FILE_NAME_INVALID", "Некорректное имя файла");
    }
}
