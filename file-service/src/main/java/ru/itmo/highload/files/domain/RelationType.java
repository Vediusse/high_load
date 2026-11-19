package ru.itmo.highload.files.domain;

import java.util.Set;
import ru.itmo.highload.common.domain.BusinessFailure;

public enum RelationType {
    SPECIFICATION,
    DISCREPANCY_ACT;

    public void requireUploadStatus(String status) {
        var allowed =
                this == SPECIFICATION
                        ? Set.of("CONFIRMED", "IN_COOKING", "READY", "COMPLETED")
                        : Set.of("READY", "COMPLETED");
        if (!allowed.contains(status))
            throw new BusinessFailure(
                    BusinessFailure.Kind.REJECTED,
                    "FILE_RELATION_STATUS",
                    "Документ недоступен для текущего статуса заказа");
    }
}
