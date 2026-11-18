package ru.itmo.highload.catering.order.adapters.in.kafka;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import ru.itmo.highload.common.events.EventCodec;
import ru.itmo.highload.catering.order.application.FileReferences;
@Component @ConditionalOnProperty(prefix="catering.events",name="enabled",havingValue="true")
public class FileEventConsumer {
    private final FileReferences links;
    private final EventCodec codec;
    public FileEventConsumer(FileReferences links,EventCodec codec) { this.links=links;this.codec=codec; }
    @KafkaListener(topics="file-events",groupId="order-file-references-v1")
    public void consume(ConsumerRecord<String,String> record) throws java.io.IOException { links.consume(codec.decode(record)); }
}
