package ru.itmo.highload.files.application;
import java.time.Instant;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import ru.itmo.highload.common.domain.*;
import ru.itmo.highload.common.domain.events.*;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.files.domain.*;
import ru.itmo.highload.files.application.ports.*;
public class FileService {
    private final FileRecords records;
    private final BinaryStorage storage;
    private final OrderAccess orders;
    private final FileLimits limits;
    public FileService(FileRecords records,BinaryStorage storage,OrderAccess orders,FileLimits limits) {
        this.records=records;this.storage=storage;this.orders=orders;this.limits=limits;
    }
    public FileView upload(UUID orderId,RelationType relation,String name,String mime,byte[] bytes,FileActor actor) {
        FileDocument.validateName(name);
        var order=orders.require(orderId,actor.bearer());relation.requireUploadStatus(order.status());
        var content=FileContent.inspect(bytes,mime,limits);
        var file=new FileDocument(UUID.randomUUID(),orderId,relation,name,content.mimeType(),content.size(),content.checksum(),
                actor.userId(),Instant.now(),FileDocument.Status.ACTIVE,UUID.randomUUID(),0);
        storage.write(file.storageKey(),bytes);
        try {
            var current=orders.require(orderId,actor.bearer());relation.requireUploadStatus(current.status());
            return FileView.from(records.create(file,event(file,current,EventType.FILE_UPLOADED,0)));
        } catch(RuntimeException error) {
            boolean orphan=false;
            try { orphan=records.find(file.id()).isEmpty(); } catch(RuntimeException lookup) { error.addSuppressed(lookup); }
            if(orphan) try { storage.removeUncommitted(file.storageKey()); } catch(RuntimeException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    public record Download(FileView metadata,byte[] bytes) { }
    public Download download(UUID id,FileActor actor) {
        var file=active(id);orders.require(file.orderId(),actor.bearer());
        var bytes=storage.read(file.storageKey());FileContent.verify(bytes,file);
        return new Download(FileView.from(file),bytes);
    }
    public PageResponse<FileView> list(UUID orderId,PageQuery query,FileActor actor) {
        orders.require(orderId,actor.bearer());var result=records.list(orderId,query);
        return new PageResponse<>(result.items().stream().map(FileView::from).toList(),query.page(),query.size(),result.hasNext());
    }
    public void delete(UUID id,FileActor actor) {
        var file=records.find(id).orElseThrow(FileService::missing);
        if(!file.uploadedBy().equals(actor.userId())) throw missing();
        var order=orders.require(file.orderId(),actor.bearer());
        if(file.status()==FileDocument.Status.DELETED) return;
        records.delete(id,actor.userId(),file.version(),event(file,order,EventType.FILE_DELETED,file.version()+1));
    }
    private FileDocument active(UUID id) {
        return records.find(id).filter(f -> f.status()==FileDocument.Status.ACTIVE).orElseThrow(FileService::missing);
    }
    private EventEnvelope event(FileDocument file,OrderSnapshot order,EventType type,long version) {
        var id=UUID.nameUUIDFromBytes((type+":"+file.id()+":"+version).getBytes(StandardCharsets.UTF_8));
        return new EventEnvelope(id,type,file.orderId(),Instant.now(),1,new EventEnvelope.Payload(order.organizationId(),order.version(),file.uploadedBy(),file.id(),file.relationType().name(),version));
    }
    private static BusinessFailure missing() { return new BusinessFailure(BusinessFailure.Kind.NOT_FOUND,"RESOURCE_NOT_FOUND","Файл не найден"); }
}
