package ru.itmo.highload.common.events;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.itmo.highload.common.domain.events.EventInbox;
public final class JdbcInbox implements EventInbox {
    private final JdbcTemplate jdbc;
    public JdbcInbox(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean claim(UUID eventId) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Inbox requires effect transaction");
        return jdbc.update("insert into processed_event(event_id) values (?) on conflict do nothing",eventId)==1;
    }
}
