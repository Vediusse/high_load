package ru.itmo.highload.notification.domain;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import ru.itmo.highload.common.domain.events.EventEnvelope;
public record Notification(UUID id,UUID eventId,UUID recipientUserId,String type,
        UUID subjectId,String text,Instant createdAt,Instant readAt) {
    public Notification {
        Objects.requireNonNull(id);Objects.requireNonNull(eventId);Objects.requireNonNull(recipientUserId);
        Objects.requireNonNull(subjectId);Objects.requireNonNull(createdAt);
        if(type==null || type.isBlank() || text==null || text.isBlank()) throw new IllegalArgumentException("Notification content required");
    }
    public static Notification confirmed(EventEnvelope event,UUID recipient) {
        UUID id=UUID.nameUUIDFromBytes((event.eventId()+":"+recipient).getBytes(StandardCharsets.UTF_8));
        return new Notification(id,event.eventId(),recipient,event.eventType().name(),event.aggregateId(),
                "Заказ подтверждён",event.occurredAt(),null);
    }
}
