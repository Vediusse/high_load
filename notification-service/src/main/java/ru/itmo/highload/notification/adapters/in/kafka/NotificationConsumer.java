package ru.itmo.highload.notification.adapters.in.kafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.highload.common.events.EventCodec;
import ru.itmo.highload.notification.application.NotificationService;
@Component @ConditionalOnProperty(prefix="catering.events",name="enabled",havingValue="true")
public class NotificationConsumer {
    private final NotificationService notifications;
    private final EventCodec codec;
    public NotificationConsumer(NotificationService notifications,EventCodec codec) { this.notifications=notifications;this.codec=codec; }
    @KafkaListener(topics="order-events",groupId="notification-v1")
    public void consume(ConsumerRecord<String,String> record) throws java.io.IOException { notifications.consume(codec.decode(record)); }
}
