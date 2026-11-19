package ru.itmo.highload.notification.adapters.in.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.BlockingRequests;
import ru.itmo.highload.common.web.Pagination;
import ru.itmo.highload.notification.application.NotificationService;
import ru.itmo.highload.notification.application.NotificationView;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Уведомления")
@StandardApiErrors
@RequiredArgsConstructor
public class NotificationController {
    private final NotificationService notifications;
    private final BlockingRequests blocking;

    @GetMapping
    @Operation(summary = "Получить собственные уведомления")
    public Mono<PageResponse<NotificationView>> list(
            @AuthenticationPrincipal Jwt actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var query = Pagination.query(page, size);
        return blocking.call(() -> notifications.list(UUID.fromString(actor.getSubject()), query));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Отметить собственное уведомление прочитанным")
    public Mono<NotificationView> read(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> notifications.read(UUID.fromString(actor.getSubject()), id));
    }
}
