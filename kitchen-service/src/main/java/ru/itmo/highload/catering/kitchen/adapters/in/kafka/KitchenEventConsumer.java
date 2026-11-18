package ru.itmo.highload.catering.kitchen.adapters.in.kafka;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.highload.catering.kitchen.application.TaskProjection;
import ru.itmo.highload.common.events.EventCodec;

@Component
@ConditionalOnProperty(prefix = "catering.events", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class KitchenEventConsumer {
    private final TaskProjection tasks;
    private final EventCodec codec;

    @KafkaListener(topics = "order-events", groupId = "kitchen-projection-v1")
    public void consume(ConsumerRecord<String, String> record) throws IOException {
        tasks.consume(codec.decode(record));
    }
}
