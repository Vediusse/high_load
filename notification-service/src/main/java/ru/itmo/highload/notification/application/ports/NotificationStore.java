package ru.itmo.highload.notification.application.ports;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.PageResult;
import ru.itmo.highload.notification.domain.Notification;

public interface NotificationStore {
    boolean processed(UUID eventId);

    PageResult<Notification> list(UUID recipient, PageQuery page);

    Optional<Notification> read(UUID recipient, UUID id);

    void recordEvent(UUID eventId, List<Notification> notifications);
}
