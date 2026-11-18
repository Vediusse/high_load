package ru.itmo.highload.catering.order.adapters.in.rest;
import java.util.UUID;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.order.application.*;
import ru.itmo.highload.catering.order.application.dto.out.FileReference;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.*;
@RestController @Tag(name="Файлы заказа") @StandardApiErrors
@PreAuthorize("hasAnyRole('ORGANIZATION_REPRESENTATIVE','CLIENT_MANAGER','KITCHEN_MANAGER')")
public class FileReferenceController {
    private final FileReferences references;
    private final OrderService orders;
    private final BlockingRequests blocking;
    public FileReferenceController(FileReferences references,OrderService orders,BlockingRequests blocking) { this.references=references;this.orders=orders;this.blocking=blocking; }
    @GetMapping("/api/v1/orders/{id}/file-references") @Operation(summary="Получить ссылки на файлы заказа из событийной проекции")
    public Mono<PageResponse<FileReference>> references(@PathVariable UUID id,@RequestParam(defaultValue="0") int page,
            @RequestParam(defaultValue="20") int size,@AuthenticationPrincipal Jwt actor) {
        var query=Pagination.query(page,size);return blocking.call(()->references.list(id,query,OrderActor.from(actor)));
    }
    public record FileAccess(UUID id,UUID organizationId,String status,long version) { }
    @Hidden @GetMapping("/internal/v1/orders/{id}/file-access")
    public Mono<FileAccess> access(@PathVariable UUID id,@AuthenticationPrincipal Jwt actor) {
        return blocking.call(()-> {
            var order=orders.getOrder(id,OrderActor.from(actor));return new FileAccess(order.id(),order.organizationId(),order.status().name(),order.version());
        });
    }
}
