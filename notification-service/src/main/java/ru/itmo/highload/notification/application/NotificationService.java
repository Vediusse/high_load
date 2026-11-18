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
        if(store.processed(event.eventId())) return;
        var ids=new LinkedHashSet<UUID>();
        var roles=switch(event.eventType()) {
            case ORDER_SUBMITTED -> java.util.List.of("KITCHEN_MANAGER");
            case ORDER_CANCELLED -> java.util.List.of("KITCHEN_MANAGER","CLIENT_MANAGER","ORGANIZATION_REPRESENTATIVE");
            case FILE_UPLOADED, FILE_DELETED -> java.util.List.of("KITCHEN_MANAGER","CLIENT_MANAGER","ORGANIZATION_REPRESENTATIVE");
            default -> java.util.List.of("ORGANIZATION_REPRESENTATIVE");
        };
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        for(var role:roles) {
            int page=0;
            while(true) {
                if(System.nanoTime()>deadline) throw new IllegalStateException("Recipient lookup deadline exceeded");
                var result=recipients.page(role,event.payload().organizationId(),page++);
                ids.addAll(result.items());
                if(!result.hasNext()) break;
            }
        }
        store.recordEvent(event.eventId(),ids.stream().map(id -> Notification.forEvent(event,id)).toList());
    }
    public ru.itmo.highload.common.dto.out.PageResponse<NotificationView> list(UUID currentUser,ru.itmo.highload.common.domain.PageQuery page) {
        var result=store.list(currentUser,page);
        return new ru.itmo.highload.common.dto.out.PageResponse<>(result.items().stream().map(NotificationView::from).toList(),page.page(),page.size(),result.hasNext());
    }
    public NotificationView read(UUID currentUser,UUID notificationId) {
        return NotificationView.from(store.read(currentUser,notificationId).orElseThrow(() ->
                new ru.itmo.highload.common.domain.BusinessFailure(ru.itmo.highload.common.domain.BusinessFailure.Kind.NOT_FOUND,"RESOURCE_NOT_FOUND","Уведомление не найдено")));
    }
}
