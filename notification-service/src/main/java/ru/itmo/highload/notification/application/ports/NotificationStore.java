package ru.itmo.highload.notification.application.ports;
import java.util.List;
import java.util.UUID;
import ru.itmo.highload.notification.domain.Notification;
public interface NotificationStore {
    boolean processed(UUID eventId);
    void recordEvent(UUID eventId,List<Notification> notifications);
}
