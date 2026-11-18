package ru.itmo.highload.catering.order.application;
import java.util.UUID;
import java.util.Set;
import ru.itmo.highload.common.domain.PageQuery;
import ru.itmo.highload.common.domain.events.*;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.catering.order.application.dto.out.FileReference;
import ru.itmo.highload.catering.order.application.ports.FileReferenceStore;
public class FileReferences {
    private final OrderService orders;
    private final FileReferenceStore links;
    private final EventInbox inbox;
    public FileReferences(OrderService orders,FileReferenceStore links,EventInbox inbox) { this.orders=orders;this.links=links;this.inbox=inbox; }
    public void consume(EventEnvelope event) {
        if(event.eventType()!=EventType.FILE_UPLOADED && event.eventType()!=EventType.FILE_DELETED)
            throw new IllegalArgumentException("Unsupported file reference event");
        if(!Set.of("SPECIFICATION","DISCREPANCY_ACT").contains(event.payload().relationType())) throw new IllegalArgumentException("Invalid relation type");
        var order=orders.getOrder(event.aggregateId());
        if(!order.organizationId().equals(event.payload().organizationId())) throw new IllegalArgumentException("File event organization mismatch");
        if(!inbox.claim(event.eventId())) return;
        links.apply(event.aggregateId(),event.payload().fileId(),event.payload().relationType(),event.payload().fileVersion(),event.eventType()==EventType.FILE_DELETED);
    }
    public PageResponse<FileReference> list(UUID id,PageQuery page,OrderAccess actor) {
        orders.getOrder(id,actor);var result=links.list(id,page);
        return new PageResponse<>(result.items(),page.page(),page.size(),result.hasNext());
    }
}
