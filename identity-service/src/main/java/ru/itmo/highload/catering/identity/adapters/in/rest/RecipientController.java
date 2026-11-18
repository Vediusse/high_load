package ru.itmo.highload.catering.identity.adapters.in.rest;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.*;
import ru.itmo.highload.catering.identity.application.UserService;
import ru.itmo.highload.catering.identity.domain.model.RoleCode;
@Hidden @RestController @RequestMapping("/internal/v1/recipients") @RequiredArgsConstructor
public class RecipientController {
    private final UserService users;
    private final BlockingRequests blocking;
    @GetMapping
    public Mono<PageResponse<UUID>> recipients(@RequestParam RoleCode role,
            @RequestParam(required=false) UUID organizationId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="50") int size) {
        var query=Pagination.query(page,size);
        return blocking.call(() -> users.recipients(role,organizationId,query));
    }
}
