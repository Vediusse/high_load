package ru.itmo.highload.files.adapters.out.persistence;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.itmo.highload.common.domain.BusinessFailure;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;
import ru.itmo.highload.common.domain.events.EventEnvelope;
import ru.itmo.highload.common.domain.events.EventOutbox;
import ru.itmo.highload.files.application.ports.FileRecords;
import ru.itmo.highload.files.domain.FileDocument;
import ru.itmo.highload.files.domain.RelationType;

public final class JdbcFiles implements FileRecords {
    private final JdbcTemplate jdbc;
    private final EventOutbox outbox;
    private final TransactionTemplate tx;

    public JdbcFiles(JdbcTemplate jdbc, EventOutbox outbox, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    private static final RowMapper<FileDocument> ROW =
            (rs, n) ->
                    new FileDocument(
                            rs.getObject("id", UUID.class),
                            rs.getObject("order_id", UUID.class),
                            RelationType.valueOf(rs.getString("relation_type")),
                            rs.getString("original_name"),
                            rs.getString("mime_type"),
                            rs.getLong("size"),
                            rs.getString("checksum"),
                            rs.getObject("uploaded_by", UUID.class),
                            rs.getTimestamp("uploaded_at").toInstant(),
                            FileDocument.Status.valueOf(rs.getString("status")),
                            rs.getObject("storage_key", UUID.class),
                            rs.getLong("version"));

    public Optional<FileDocument> find(UUID id) {
        return jdbc.query("select * from file_document where id=?", ROW, id).stream().findFirst();
    }

    public PageResult<FileDocument> list(UUID orderId, PageQuery page) {
        var rows =
                jdbc.query(
                        "select * from file_document where order_id=? and status='ACTIVE' order by uploaded_at desc,id desc limit ? offset ?",
                        ROW,
                        orderId,
                        page.size() + 1,
                        page.offset());
        return new PageResult<>(
                rows.subList(0, Math.min(rows.size(), page.size())),
                page.page(),
                page.size(),
                -1,
                rows.size() > page.size());
    }

    public FileDocument create(FileDocument file, EventEnvelope event) {
        return tx.execute(status -> {
            jdbc.update(
                    "insert into file_document(id,order_id,relation_type,original_name,mime_type,size,checksum,uploaded_by,uploaded_at,status,storage_key,version) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                    file.id(),
                    file.orderId(),
                    file.relationType().name(),
                    file.originalName(),
                    file.mimeType(),
                    file.size(),
                    file.checksum(),
                    file.uploadedBy(),
                    Timestamp.from(file.uploadedAt()),
                    file.status().name(),
                    file.storageKey(),
                    file.version());
            outbox.append(event);
            return file;
        });
    }

    public void delete(UUID id, UUID owner, long version, EventEnvelope event) {
        tx.executeWithoutResult(status -> {
            int changed =
                    jdbc.update(
                            "update file_document set status='DELETED',version=version+1 where id=? and uploaded_by=? and version=? and status='ACTIVE'",
                            id,
                            owner,
                            version);
            if (changed == 1) {
                outbox.append(event);
                return;
            }
            var file =
                    find(id).filter(f -> f.uploadedBy().equals(owner))
                            .orElseThrow(() ->
                                    new BusinessFailure(
                                            BusinessFailure.Kind.NOT_FOUND,
                                            "RESOURCE_NOT_FOUND",
                                            "Файл не найден"));
            if (file.status() != FileDocument.Status.DELETED)
                throw new BusinessFailure(
                        BusinessFailure.Kind.CONFLICT,
                        "FILE_VERSION_CONFLICT",
                        "Версия файла изменилась");
        });
    }
}
