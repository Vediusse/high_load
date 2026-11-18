package ru.itmo.highload.notification.adapters.in.rest;
import java.util.UUID;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.*;
import ru.itmo.highload.notification.application.*;
@RestController @RequestMapping("/api/v1/notifications") @Tag(name="Уведомления") @StandardApiErrors
public class NotificationController {
    private final NotificationService notifications;
    private final BlockingRequests blocking;
    public NotificationController(NotificationService notifications,BlockingRequests blocking) { this.notifications=notifications;this.blocking=blocking; }
    @GetMapping @Operation(summary="Получить собственные уведомления")
    public Mono<PageResponse<NotificationView>> list(@AuthenticationPrincipal Jwt actor,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        var query=Pagination.query(page,size);
        return blocking.call(() -> notifications.list(UUID.fromString(actor.getSubject()),query));
    }
    @PostMapping("/{id}/read") @Operation(summary="Отметить собственное уведомление прочитанным")
    public Mono<NotificationView> read(@PathVariable UUID id,@AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> notifications.read(UUID.fromString(actor.getSubject()),id));
    }
}
