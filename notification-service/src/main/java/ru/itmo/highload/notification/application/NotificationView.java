package ru.itmo.highload.notification.application;
import java.time.Instant;
import java.util.UUID;
import ru.itmo.highload.notification.domain.Notification;
public record NotificationView(UUID id,String type,UUID subjectId,String text,Instant createdAt,Instant readAt) {
    public static NotificationView from(Notification n) { return new NotificationView(n.id(),n.type(),n.subjectId(),n.text(),n.createdAt(),n.readAt()); }
}
