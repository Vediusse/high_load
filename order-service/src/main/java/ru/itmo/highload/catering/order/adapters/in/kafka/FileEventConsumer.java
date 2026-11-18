package ru.itmo.highload.catering.order.adapters.in.kafka;

import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import ru.itmo.highload.catering.order.application.FileReferences;
import ru.itmo.highload.common.events.EventCodec;

@Component
@ConditionalOnProperty(prefix = "catering.events", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class FileEventConsumer {
    private final FileReferences links;
    private final EventCodec codec;

    @KafkaListener(topics = "file-events", groupId = "order-file-references-v1")
    public void consume(ConsumerRecord<String, String> record) throws IOException {
        links.consume(codec.decode(record));
    }
}
