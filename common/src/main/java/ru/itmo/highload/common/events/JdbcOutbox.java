package ru.itmo.highload.common.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.itmo.highload.common.domain.events.EventEnvelope;
import ru.itmo.highload.common.domain.events.EventOutbox;

@RequiredArgsConstructor
public final class JdbcOutbox implements EventOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public void append(EventEnvelope event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Outbox requires the business transaction");
        try {
            // Serialize appends for an order, including concurrent file uploads, through commit.
            jdbc.queryForObject(
                    "select pg_advisory_xact_lock(?)",
                    Object.class,
                    event.aggregateId().getMostSignificantBits()
                            ^ event.aggregateId().getLeastSignificantBits());
            jdbc.update(
                    "insert into outbox_event(event_id,aggregate_id,topic,payload) values (?,?,?,cast(? as jsonb)) on conflict(event_id) do nothing",
                    event.eventId(),
                    event.aggregateId(),
                    event.eventType().topic(),
                    mapper.writeValueAsString(event));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid event", error);
        }
    }
}
