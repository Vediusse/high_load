package ru.itmo.highload.notification.adapters.in.kafka;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.highload.common.events.EventCodec;
import ru.itmo.highload.notification.application.NotificationService;

@Component
@ConditionalOnProperty(prefix = "catering.events", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class NotificationConsumer {
    private final NotificationService notifications;
    private final EventCodec codec;

    @KafkaListener(
            topics = {"order-events", "production-events", "file-events"},
            groupId = "notification-v1")
    public void consume(ConsumerRecord<String, String> record) throws IOException {
        notifications.consume(codec.decode(record));
    }
}
