package ru.itmo.highload.files.application;

import java.util.UUID;
import ru.itmo.highload.files.domain.RelationType;

public record UploadFileCommand(
        UUID orderId,
        RelationType relationType,
        String originalName,
        String mimeType,
        byte[] content) {}
