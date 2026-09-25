package ru.itmo.highload.catering.order.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.common.dto.PageResponse;
import ru.itmo.highload.catering.common.web.BlockingRequests;
import ru.itmo.highload.catering.common.web.Pagination;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.ProductionCommand;
import ru.itmo.highload.catering.order.service.OrderService;
import ru.itmo.highload.catering.order.service.ProductionCommands;

@Hidden
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
public class ProductionOrderController {
    private final BlockingRequests blocking;
    private final OrderService orders;
    private final ProductionCommands commands;

    @GetMapping("/production")
    public Mono<PageResponse<OrderResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return blocking.call(() -> orders.productionQueue(Pagination.pageRequest(page, size)));
    }

    @GetMapping("/{id}")
    public Mono<OrderResponse> get(@PathVariable UUID id) {
        return blocking.call(() -> orders.getOrder(id));
    }

    @PostMapping("/{id}/production-commands")
    public Mono<OrderResponse> command(@PathVariable UUID id, @Valid @RequestBody ProductionCommand request) {
        return blocking.call(() -> commands.execute(id, request));
    }
}
