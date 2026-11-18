package ru.itmo.highload.catering.order.application.ports;

import java.util.UUID;
import ru.itmo.highload.catering.order.application.dto.out.FileReference;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;

public interface FileReferenceStore {
    void apply(UUID orderId, UUID fileId, String relationType, long version, boolean deleted);

    PageResult<FileReference> list(UUID orderId, PageQuery query);
}
