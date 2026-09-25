package ru.itmo.highload.catering.production.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.common.config.StandardApiErrors;
import ru.itmo.highload.catering.common.web.BlockingRequests;
import ru.itmo.highload.catering.order.dto.*;
import ru.itmo.highload.catering.production.service.ProductionService;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Заказы")
@StandardApiErrors
@RequiredArgsConstructor
public class ProductionController {
    private final BlockingRequests blocking;
    private final ProductionService production;

    @PostMapping("/{id}/start-cooking")
    @Operation(summary = "Начать приготовление подтверждённого заказа")
    public Mono<ResponseEntity<OrderResponse>> startCooking(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return blocking.call(() -> ResponseEntity.ok(production.execute(id, request.expectedVersion(), ProductionAction.START_COOKING)));
    }

    @PostMapping("/{id}/mark-ready")
    @Operation(summary = "Отметить заказ готовым")
    public Mono<ResponseEntity<OrderResponse>> markReady(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return blocking.call(() -> ResponseEntity.ok(production.execute(id, request.expectedVersion(), ProductionAction.MARK_READY)));
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Завершить выданный заказ")
    public Mono<ResponseEntity<OrderResponse>> complete(
            @PathVariable UUID id,
            @Valid @RequestBody OrderCommandRequest request) {
        return blocking.call(() -> ResponseEntity.ok(production.execute(id, request.expectedVersion(), ProductionAction.COMPLETE)));
    }

}
