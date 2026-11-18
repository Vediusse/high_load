package ru.itmo.highload.catering.kitchen.adapters.in.kafka;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.highload.common.events.EventCodec;
import ru.itmo.highload.catering.kitchen.application.TaskProjection;
@Component @ConditionalOnProperty(prefix="catering.events",name="enabled",havingValue="true")
public class KitchenEventConsumer {
    private final TaskProjection tasks;
    private final EventCodec codec;
    public KitchenEventConsumer(TaskProjection tasks,EventCodec codec) { this.tasks=tasks;this.codec=codec; }
    @KafkaListener(topics="order-events",groupId="kitchen-projection-v1")
    public void consume(ConsumerRecord<String,String> record) throws java.io.IOException { tasks.consume(codec.decode(record)); }
}
