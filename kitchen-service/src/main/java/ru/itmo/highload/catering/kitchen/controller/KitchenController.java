package ru.itmo.highload.catering.kitchen.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.config.StandardApiErrors;
import ru.itmo.highload.common.web.BlockingRequests;
import ru.itmo.highload.catering.order.dto.*;
import ru.itmo.highload.catering.kitchen.service.KitchenService;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Заказы")
@StandardApiErrors
@RequiredArgsConstructor
public class KitchenController {
    private final BlockingRequests blocking;
    private final KitchenService kitchen;

    @PostMapping("/{id}/start-cooking")
    @Operation(summary = "Начать приготовление подтверждённого заказа")
    public Mono<ResponseEntity<OrderResponse>> startCooking(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return blocking.call(() -> ResponseEntity.ok(kitchen.execute(id, request.expectedVersion(), KitchenAction.START_COOKING)));
    }

    @PostMapping("/{id}/mark-ready")
    @Operation(summary = "Отметить заказ готовым")
    public Mono<ResponseEntity<OrderResponse>> markReady(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return blocking.call(() -> ResponseEntity.ok(kitchen.execute(id, request.expectedVersion(), KitchenAction.MARK_READY)));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Завершить выданный заказ")
    public Mono<ResponseEntity<OrderResponse>> complete(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return blocking.call(() -> ResponseEntity.ok(kitchen.execute(id, request.expectedVersion(), KitchenAction.COMPLETE)));
    }

}
