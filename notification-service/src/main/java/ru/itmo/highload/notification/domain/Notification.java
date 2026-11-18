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
    public static Notification forEvent(EventEnvelope event,UUID recipient) {
        UUID id=UUID.nameUUIDFromBytes((event.eventId()+":"+recipient).getBytes(StandardCharsets.UTF_8));
        return new Notification(id,event.eventId(),recipient,event.eventType().name(),event.aggregateId(),
                switch(event.eventType()) {
                    case ORDER_SUBMITTED -> "Заказ отправлен на кухню";
                    case ORDER_CONFIRMED -> "Заказ подтверждён";
                    case ORDER_REJECTED -> "Заказ отклонён";
                    case ORDER_CANCELLED -> "Заказ отменён";
                    case COOKING_STARTED -> "Приготовление заказа начато";
                    case ORDER_READY -> "Заказ готов";
                    case ORDER_COMPLETED -> "Заказ выдан";
                    case FILE_UPLOADED -> "Файл заказа загружен";
                    case FILE_DELETED -> "Файл заказа удалён";
                },event.occurredAt(),null);
    }
}
