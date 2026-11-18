package ru.itmo.highload.common.events;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Acknowledged, at-least-once delivery. Rows remain durable across outages/restarts. */
public final class OutboxRelay {
    @FunctionalInterface
    public interface Sender {
        void send(String topic, String key, String payload) throws Exception;
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Sender sender;

    public OutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager manager, Sender sender) {
        this.jdbc = jdbc;
        this.sender = sender;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(60);
    }

    public void publishBatch() {
        transaction.executeWithoutResult(status -> {
            if (!Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "select pg_try_advisory_xact_lock(73461920261118)", Boolean.class)))
                return;
            var rows =
                    jdbc.query(
                            """
                            select e.event_id,e.aggregate_id,e.topic,e.payload::text,e.attempts from outbox_event e
                            where e.sent_at is null and e.next_attempt_at <= clock_timestamp()
                              and not exists(select 1 from outbox_event earlier where earlier.aggregate_id=e.aggregate_id
                                and earlier.sent_at is null and earlier.sequence<e.sequence)
                            order by e.sequence limit 10 for update of e skip locked
                            """,
                            (rs, n) ->
                                    new Pending(
                                            rs.getObject(1, UUID.class),
                                            rs.getObject(2, UUID.class),
                                            rs.getString(3),
                                            rs.getString(4),
                                            rs.getInt(5)));
            for (var row : rows) {
                try {
                    sender.send(row.topic(), row.orderId().toString(), row.payload());
                    jdbc.update(
                            "update outbox_event set sent_at=clock_timestamp(),last_error=null where event_id=?",
                            row.id());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Relay interrupted", error);
                } catch (Exception error) {
                    int delay = 1 << Math.min(row.attempts(), 5);
                    jdbc.update(
                            "update outbox_event set attempts=attempts+1,next_attempt_at=clock_timestamp()+(? * interval '1 second'),last_error=? where event_id=?",
                            delay,
                            error.getClass().getSimpleName(),
                            row.id());
                    LoggerFactory.getLogger(getClass())
                            .warn(
                                    "Outbox delivery deferred: eventId={}, failure={}",
                                    row.id(),
                                    error.getClass().getSimpleName());
                }
            }
        });
    }

    private record Pending(UUID id, UUID orderId, String topic, String payload, int attempts) {}
}
