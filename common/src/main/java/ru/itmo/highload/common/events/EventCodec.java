package ru.itmo.highload.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import ru.itmo.highload.common.domain.events.EventEnvelope;

@RequiredArgsConstructor
public final class EventCodec {
    private final ObjectMapper mapper;

    public EventEnvelope decode(ConsumerRecord<String, String> record) throws IOException {
        var event = mapper.readValue(record.value(), EventEnvelope.class);
        if (!event.aggregateId().toString().equals(record.key())
                || !event.eventType().topic().equals(record.topic()))
            throw new IllegalArgumentException("Event key/topic mismatch");
        return event;
    }
}
