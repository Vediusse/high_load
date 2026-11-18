package ru.itmo.highload.catering.order.adapters.out.persistence;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.itmo.highload.catering.order.application.dto.out.FileReference;
import ru.itmo.highload.catering.order.application.ports.FileReferenceStore;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;

@Repository
@RequiredArgsConstructor
public class JdbcFileReferences implements FileReferenceStore {
    private final JdbcTemplate jdbc;

    public void apply(UUID orderId, UUID fileId, String type, long version, boolean deleted) {
        var existing =
                jdbc.query(
                        "select order_id,relation_type from file_reference where file_id=?",
                        (rs, n) -> new Identity(rs.getObject(1, UUID.class), rs.getString(2)),
                        fileId);
        if (!existing.isEmpty()
                && (!existing.getFirst().orderId().equals(orderId)
                        || !existing.getFirst().type().equals(type)))
            throw new IllegalArgumentException("File relation identity cannot change");
        jdbc.update(
                """
                insert into file_reference(file_id,order_id,relation_type,file_version,deleted) values (?,?,?,?,?)
                on conflict(file_id) do update set file_version=excluded.file_version,deleted=excluded.deleted
                where file_reference.file_version<excluded.file_version
                  and file_reference.order_id=excluded.order_id and file_reference.relation_type=excluded.relation_type
                """,
                fileId,
                orderId,
                type,
                version,
                deleted);
    }

    public PageResult<FileReference> list(UUID orderId, PageQuery page) {
        var rows =
                jdbc.query(
                        "select file_id,relation_type from file_reference where order_id=? and not deleted order by file_id limit ? offset ?",
                        (rs, n) -> new FileReference(rs.getObject(1, UUID.class), rs.getString(2)),
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

    private record Identity(UUID orderId, String type) {}
}
