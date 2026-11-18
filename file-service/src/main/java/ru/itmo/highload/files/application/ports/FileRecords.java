package ru.itmo.highload.files.application.ports;
import java.util.*;
import ru.itmo.highload.common.domain.*;
import ru.itmo.highload.common.domain.events.EventEnvelope;
import ru.itmo.highload.files.domain.FileDocument;
public interface FileRecords {
    Optional<FileDocument> find(UUID id);
    PageResult<FileDocument> list(UUID orderId,PageQuery query);
    FileDocument create(FileDocument file,EventEnvelope event);
    void delete(UUID id,UUID owner,long expectedVersion,EventEnvelope event);
}
