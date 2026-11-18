package ru.itmo.highload.files.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.common.domain.BusinessFailure;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.events.EventEnvelope;
import ru.itmo.highload.common.domain.events.EventType;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.files.application.ports.BinaryStorage;
import ru.itmo.highload.files.application.ports.FileRecords;
import ru.itmo.highload.files.application.ports.OrderAccess;
import ru.itmo.highload.files.domain.FileContent;
import ru.itmo.highload.files.domain.FileDocument;
import ru.itmo.highload.files.domain.FileLimits;

@RequiredArgsConstructor
public class FileService {
    private final FileRecords records;
    private final BinaryStorage storage;
    private final OrderAccess orders;
    private final FileLimits limits;

    public FileView upload(UploadFileCommand command, FileActor actor) {
        FileDocument.validateName(command.originalName());
        var order = orders.require(command.orderId(), actor.bearer());
        command.relationType().requireUploadStatus(order.status());
        var content = FileContent.inspect(command.content(), command.mimeType(), limits);
        var file =
                new FileDocument(
                        UUID.randomUUID(),
                        command.orderId(),
                        command.relationType(),
                        command.originalName(),
                        content.mimeType(),
                        content.size(),
                        content.checksum(),
                        actor.userId(),
                        Instant.now(),
                        FileDocument.Status.ACTIVE,
                        UUID.randomUUID(),
                        0);
        storage.write(file.storageKey(), command.content());
        try {
            var current = orders.require(command.orderId(), actor.bearer());
            command.relationType().requireUploadStatus(current.status());
            return FileView.from(
                    records.create(file, event(file, current, EventType.FILE_UPLOADED, 0)));
        } catch (RuntimeException error) {
            removeUncommittedFile(file, error);
            throw error;
        }
    }

    private void removeUncommittedFile(FileDocument file, RuntimeException original) {
        try {
            if (records.find(file.id()).isEmpty()) {
                storage.removeUncommitted(file.storageKey());
            }
        } catch (RuntimeException cleanup) {
            original.addSuppressed(cleanup);
        }
    }

    public record Download(FileView metadata, byte[] bytes) {}

    public Download download(UUID id, FileActor actor) {
        var file = active(id);
        orders.require(file.orderId(), actor.bearer());
        var bytes = storage.read(file.storageKey());
        FileContent.verify(bytes, file);
        return new Download(FileView.from(file), bytes);
    }

    public PageResponse<FileView> list(UUID orderId, PageQuery query, FileActor actor) {
        orders.require(orderId, actor.bearer());
        var result = records.list(orderId, query);
        return new PageResponse<>(
                result.items().stream().map(FileView::from).toList(),
                query.page(),
                query.size(),
                result.hasNext());
    }

    public void delete(UUID id, FileActor actor) {
        var file = records.find(id).orElseThrow(FileService::missing);
        if (!file.uploadedBy().equals(actor.userId())) throw missing();
        var order = orders.require(file.orderId(), actor.bearer());
        if (file.status() == FileDocument.Status.DELETED) return;
        records.delete(
                id,
                actor.userId(),
                file.version(),
                event(file, order, EventType.FILE_DELETED, file.version() + 1));
    }

    private FileDocument active(UUID id) {
        return records.find(id)
                .filter(f -> f.status() == FileDocument.Status.ACTIVE)
                .orElseThrow(FileService::missing);
    }

    private EventEnvelope event(
            FileDocument file, OrderSnapshot order, EventType type, long version) {
        var id =
                UUID.nameUUIDFromBytes(
                        (type + ":" + file.id() + ":" + version).getBytes(StandardCharsets.UTF_8));
        return new EventEnvelope(
                id,
                type,
                file.orderId(),
                Instant.now(),
                1,
                new EventEnvelope.Payload(
                        order.organizationId(),
                        order.version(),
                        file.uploadedBy(),
                        file.id(),
                        file.relationType().name(),
                        version));
    }

    private static BusinessFailure missing() {
        return new BusinessFailure(
                BusinessFailure.Kind.NOT_FOUND, "RESOURCE_NOT_FOUND", "Файл не найден");
    }
}
