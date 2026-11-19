package ru.itmo.highload.notification.application;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import ru.itmo.highload.common.domain.BusinessFailure;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.events.EventEnvelope;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.notification.application.ports.NotificationStore;
import ru.itmo.highload.notification.application.ports.RecipientDirectory;
import ru.itmo.highload.notification.domain.Notification;

@RequiredArgsConstructor
public class NotificationService {
    private final NotificationStore store;
    private final RecipientDirectory recipients;

    public void consume(EventEnvelope event) {
        if (store.processed(event.eventId())) return;
        var ids = new LinkedHashSet<UUID>();
        var roles =
                switch (event.eventType()) {
                    case ORDER_SUBMITTED -> List.of("KITCHEN_MANAGER");
                    case ORDER_CANCELLED ->
                            List.of(
                                    "KITCHEN_MANAGER",
                                    "CLIENT_MANAGER",
                                    "ORGANIZATION_REPRESENTATIVE");
                    case FILE_UPLOADED, FILE_DELETED ->
                            List.of(
                                    "KITCHEN_MANAGER",
                                    "CLIENT_MANAGER",
                                    "ORGANIZATION_REPRESENTATIVE");
                    default -> List.of("ORGANIZATION_REPRESENTATIVE");
                };
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        for (var role : roles) {
            int page = 0;
            while (true) {
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("Recipient lookup deadline exceeded");
                var result = recipients.page(role, event.payload().organizationId(), page++);
                ids.addAll(result.items());
                if (!result.hasNext()) break;
            }
        }
        store.recordEvent(
                event.eventId(), ids.stream().map(id -> Notification.forEvent(event, id)).toList());
    }

    public PageResponse<NotificationView> list(UUID currentUser, PageQuery page) {
        var result = store.list(currentUser, page);
        return new PageResponse<>(
                result.items().stream().map(NotificationView::from).toList(),
                page.page(),
                page.size(),
                result.hasNext());
    }

    public NotificationView read(UUID currentUser, UUID notificationId) {
        return NotificationView.from(
                store.read(currentUser, notificationId)
                        .orElseThrow(() ->
                                new BusinessFailure(
                                        BusinessFailure.Kind.NOT_FOUND,
                                        "RESOURCE_NOT_FOUND",
                                        "Уведомление не найдено")));
    }
}
