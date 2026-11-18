package ru.itmo.highload.files.application;

import java.time.Instant;
import java.util.UUID;
import ru.itmo.highload.files.domain.FileDocument;
import ru.itmo.highload.files.domain.RelationType;

public record FileView(
        UUID id,
        UUID orderId,
        RelationType relationType,
        String originalName,
        String mimeType,
        long size,
        String checksum,
        UUID uploadedBy,
        Instant uploadedAt,
        FileDocument.Status status) {
    public static FileView from(FileDocument f) {
        return new FileView(
                f.id(),
                f.orderId(),
                f.relationType(),
                f.originalName(),
                f.mimeType(),
                f.size(),
                f.checksum(),
                f.uploadedBy(),
                f.uploadedAt(),
                f.status());
    }
}
