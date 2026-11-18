package ru.itmo.highload.files.adapters.in.rest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Semaphore;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.*;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.web.*;
import ru.itmo.highload.common.dto.out.*;
import ru.itmo.highload.common.error.*;
import ru.itmo.highload.files.domain.*;
import ru.itmo.highload.files.application.*;
@RestController @Tag(name="Файлы заказов") @StandardApiErrors
@PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE','CLIENT_MANAGER','KITCHEN_MANAGER')")
public class FileController {
    private final FileService files;
    private final BlockingRequests blocking;
    private final FileLimits limits;
    private final Semaphore uploads=new Semaphore(2);
    public FileController(FileService files,BlockingRequests blocking,FileLimits limits) { this.files=files;this.blocking=blocking;this.limits=limits; }
    @PostMapping(value="/api/v1/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary="Загрузить готовую спецификацию или акт расхождения")
    public Mono<ResponseEntity<FileView>> upload(@RequestPart String orderId,@RequestPart String relationType,
            @RequestPart("file") FilePart part,@AuthenticationPrincipal Jwt jwt) {
        UUID order;RelationType relation;
        try { order=UUID.fromString(orderId); } catch(IllegalArgumentException bad) { throw invalid("orderId"); }
        try { relation=RelationType.valueOf(relationType); } catch(IllegalArgumentException bad) { throw invalid("relationType"); }
        var contentType=part.headers().getContentType();
        String mime=contentType==null?null:contentType.getType()+"/"+contentType.getSubtype();
        return Mono.usingWhen(Mono.fromSupplier(()-> {
            if(!uploads.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"SERVICE_BUSY","Загрузка занята, повторите запрос позже");
            return true;
        }),permit -> DataBufferUtils.join(part.content(),limits.maxBytes()).map(buffer -> {
            try { byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);return bytes; }
            finally { DataBufferUtils.release(buffer); }
        }).defaultIfEmpty(new byte[0]).flatMap(bytes -> blocking.call(()->files.upload(order,relation,part.filename(),mime,bytes,actor(jwt))))
                .map(file -> ResponseEntity.created(URI.create("/api/v1/files/"+file.id())).body(file)),
                permit -> release(part),(permit,error)->release(part),permit->release(part));
    }
    private Mono<Void> release(FilePart part) { return part.delete().onErrorResume(error->Mono.empty()).doFinally(signal->uploads.release()); }
    @GetMapping("/api/v1/files/{id}") @Operation(summary="Скачать доступный файл с проверкой checksum")
    public Mono<ResponseEntity<byte[]>> download(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) {
        return blocking.call(()->files.download(id,actor(jwt))).map(result -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.metadata().mimeType())).contentLength(result.bytes().length)
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.metadata().originalName(),StandardCharsets.UTF_8).build().toString())
                .body(result.bytes()));
    }
    @GetMapping("/api/v1/orders/{id}/files") @Operation(summary="Получить доступные файлы заказа")
    public Mono<PageResponse<FileView>> list(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,@AuthenticationPrincipal Jwt jwt) {
        var query=Pagination.query(page,size);return blocking.call(()->files.list(id,query,actor(jwt)));
    }
    @DeleteMapping("/api/v1/files/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Operation(summary="Логически удалить собственный файл")
    public Mono<Void> delete(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt) {
        return blocking.call(()-> {files.delete(id,actor(jwt));return true;}).then();
    }
    private static FileActor actor(Jwt jwt) { return new FileActor(UUID.fromString(jwt.getSubject()),"Bearer "+jwt.getTokenValue()); }
    private static RequestValidationException invalid(String field) { return new RequestValidationException(List.of(new FieldErrorResponse(field,"имеет неверный формат"))); }
}
