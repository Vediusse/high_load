package ru.itmo.highload.catering.order.adapters.in.rest;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.order.application.dto.in.KitchenCommand;
import ru.itmo.highload.catering.order.application.dto.in.OrderStatesRequest;
import ru.itmo.highload.catering.order.application.dto.out.OrderResponse;
import ru.itmo.highload.catering.order.application.dto.out.OrderState;
import ru.itmo.highload.catering.order.application.KitchenCommands;
import ru.itmo.highload.catering.order.application.OrderService;
import ru.itmo.highload.catering.order.application.OrderAccess;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.BlockingRequests;
import ru.itmo.highload.common.web.Pagination;

@Hidden
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
public class KitchenOrderController {
    private final BlockingRequests blocking;
    private final OrderService orders;
    private final KitchenCommands commands;

    @PostMapping("/states")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    public Flux<OrderState> states(@Valid @RequestBody OrderStatesRequest request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orders.states(request.ids(), OrderActor.from(actor))).flatMapMany(Flux::fromIterable);
    }

    @GetMapping("/kitchen")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    public Mono<PageResponse<OrderResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orders.kitchenQueue(Pagination.query(page, size), OrderActor.from(actor)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    public Mono<OrderResponse> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> orders.getOrder(id, OrderActor.from(actor)));
    }

    @PostMapping("/{id}/kitchen-commands")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    public Mono<OrderResponse> command(
            @PathVariable UUID id, @Valid @RequestBody KitchenCommand request, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> commands.execute(id, request, OrderActor.from(actor)));
    }
}
