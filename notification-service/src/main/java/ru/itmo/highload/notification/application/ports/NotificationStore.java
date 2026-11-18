package ru.itmo.highload.notification.application.ports;
import java.util.List;
import java.util.UUID;
import ru.itmo.highload.notification.domain.Notification;
public interface NotificationStore {
    boolean processed(UUID eventId);
    ru.itmo.highload.common.domain.PageResult<Notification> list(UUID recipient,ru.itmo.highload.common.domain.PageQuery page);
    java.util.Optional<Notification> read(UUID recipient,UUID id);
    void recordEvent(UUID eventId,List<Notification> notifications);
}
