package ru.itmo.highload.notification.application.ports;

import java.util.UUID;
import ru.itmo.highload.common.dto.out.PageResponse;

public interface RecipientDirectory {
    PageResponse<UUID> page(String role, UUID organizationId, int page);
}
