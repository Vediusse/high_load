package ru.itmo.highload.notification.adapters.out.persistence;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.itmo.highload.notification.application.ports.NotificationStore;
import ru.itmo.highload.notification.domain.Notification;
public final class JdbcNotifications implements NotificationStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public JdbcNotifications(JdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc;transaction=new TransactionTemplate(manager);transaction.setTimeout(10); }
    public boolean processed(UUID eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from processed_event where event_id=?)",Boolean.class,eventId));
    }
    private static final org.springframework.jdbc.core.RowMapper<Notification> ROW=(rs,n) -> new Notification(
            rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),rs.getObject("recipient_user_id",UUID.class),
            rs.getString("type"),rs.getObject("subject_id",UUID.class),rs.getString("text"),
            rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("read_at")==null?null:rs.getTimestamp("read_at").toInstant());
    public ru.itmo.highload.common.domain.PageResult<Notification> list(UUID recipient,ru.itmo.highload.common.domain.PageQuery page) {
        var rows=jdbc.query("select * from notification where recipient_user_id=? order by created_at desc,id desc limit ? offset ?",ROW,recipient,page.size()+1,page.getOffset());
        boolean more=rows.size()>page.size();
        return new ru.itmo.highload.common.domain.PageResult<>(rows.subList(0,Math.min(rows.size(),page.size())),page.page(),page.size(),-1,more);
    }
    public java.util.Optional<Notification> read(UUID recipient,UUID id) {
        return jdbc.query("update notification set read_at=coalesce(read_at,clock_timestamp()) where id=? and recipient_user_id=? returning *",ROW,id,recipient).stream().findFirst();
    }
    public void recordEvent(UUID eventId,List<Notification> notifications) {
        transaction.executeWithoutResult(status -> {
            if(jdbc.update("insert into processed_event(event_id) values (?) on conflict do nothing",eventId)==0) return;
            jdbc.batchUpdate("insert into notification(id,event_id,recipient_user_id,type,subject_id,text,created_at) values (?,?,?,?,?,?,?) on conflict(event_id,recipient_user_id) do nothing",
                notifications,50,(ps,n) -> {
                    ps.setObject(1,n.id());ps.setObject(2,n.eventId());ps.setObject(3,n.recipientUserId());
                    ps.setString(4,n.type());ps.setObject(5,n.subjectId());ps.setString(6,n.text());ps.setTimestamp(7,Timestamp.from(n.createdAt()));
                });
        });
    }
}
