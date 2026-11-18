package ru.itmo.highload.catering.kitchen.adapters.in.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.kitchen.application.KitchenService;
import ru.itmo.highload.catering.kitchen.application.dto.in.OrderCommandRequest;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.domain.model.KitchenAction;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.web.BlockingRequests;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Заказы")
@StandardApiErrors
@RequiredArgsConstructor
public class KitchenController {
    private final BlockingRequests blocking;
    private final KitchenService kitchen;

    @PostMapping("/{id}/start-cooking")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    @Operation(summary = "Начать приготовление подтверждённого заказа")
    public Mono<OrderResponse> startCooking(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request,
            @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() ->
                kitchen.execute(
                        id,
                        request.expectedVersion(),
                        KitchenAction.START_COOKING,
                        "Bearer " + actor.getTokenValue()));
    }

    @PostMapping("/{id}/mark-ready")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    @Operation(summary = "Отметить заказ готовым")
    public Mono<OrderResponse> markReady(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request,
            @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() ->
                kitchen.execute(
                        id,
                        request.expectedVersion(),
                        KitchenAction.MARK_READY,
                        "Bearer " + actor.getTokenValue()));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    @Operation(summary = "Завершить выданный заказ")
    public Mono<OrderResponse> complete(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request,
            @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() ->
                kitchen.execute(
                        id,
                        request.expectedVersion(),
                        KitchenAction.COMPLETE,
                        "Bearer " + actor.getTokenValue()));
    }
}
