package ru.itmo.highload.common.domain.events;
import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.json.JsonMapper;
class EventEnvelopeTest {
    @Test void roundTripEveryEventAndRejectUnsupportedSchema() throws Exception {
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var payload = new EventEnvelope.Payload(UUID.randomUUID(), 7, UUID.randomUUID(), UUID.randomUUID(), "SPECIFICATION", 1L);
        for (var type : EventType.values()) {
            var event = new EventEnvelope(UUID.randomUUID(),type,UUID.randomUUID(),Instant.now(),1,payload);
            assertThat(mapper.readValue(mapper.writeValueAsBytes(event), EventEnvelope.class)).isEqualTo(event);
            assertThat(type.topic()).isIn("order-events","production-events","file-events");
        }
        assertThatThrownBy(() -> new EventEnvelope(UUID.randomUUID(),EventType.ORDER_CONFIRMED,UUID.randomUUID(),Instant.now(),2,payload)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventEnvelope(UUID.randomUUID(),EventType.FILE_UPLOADED,UUID.randomUUID(),Instant.now(),1,
                EventEnvelope.Payload.order(UUID.randomUUID(),1,null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventEnvelope.Payload.order(UUID.randomUUID(),-1,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
