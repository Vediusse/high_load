package ru.itmo.highload.notification.application;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;
import ru.itmo.highload.common.domain.events.*;
import ru.itmo.highload.notification.application.ports.*;
import ru.itmo.highload.notification.domain.Notification;
public class NotificationService {
    private final NotificationStore store;
    private final RecipientDirectory recipients;
    public NotificationService(NotificationStore store,RecipientDirectory recipients) { this.store=store;this.recipients=recipients; }
    public void consume(EventEnvelope event) {
        if(event.eventType()!=EventType.ORDER_CONFIRMED) throw new IllegalArgumentException("Unsupported notification event");
        if(store.processed(event.eventId())) return;
        var ids=new LinkedHashSet<UUID>();
        int page=0;
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(true) {
            if(System.nanoTime()>deadline) throw new IllegalStateException("Recipient lookup deadline exceeded");
            var result=recipients.page("ORGANIZATION_REPRESENTATIVE",event.payload().organizationId(),page++);
            ids.addAll(result.items());
            if(!result.hasNext()) break;
        }
        store.recordEvent(event.eventId(),ids.stream().map(id -> Notification.confirmed(event,id)).toList());
    }
}
