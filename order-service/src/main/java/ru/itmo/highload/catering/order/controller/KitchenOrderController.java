package ru.itmo.highload.catering.order.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.order.dto.KitchenCommand;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.OrderState;
import ru.itmo.highload.catering.order.dto.OrderStatesRequest;
import ru.itmo.highload.catering.order.service.KitchenCommands;
import ru.itmo.highload.catering.order.service.OrderService;
import ru.itmo.highload.common.dto.PageResponse;
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
    public Mono<List<OrderState>> states(@Valid @RequestBody OrderStatesRequest request) {
        return blocking.call(() -> orders.states(request.ids()));
    }

    @GetMapping("/kitchen")
    public Mono<PageResponse<OrderResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return blocking.call(() -> orders.kitchenQueue(Pagination.pageRequest(page, size)));
    }

    @GetMapping("/{id}")
    public Mono<OrderResponse> get(@PathVariable UUID id) {
        return blocking.call(() -> orders.getOrder(id));
    }

    @PostMapping("/{id}/kitchen-commands")
    public Mono<OrderResponse> command(@PathVariable UUID id, @Valid @RequestBody KitchenCommand request) {
        return blocking.call(() -> commands.execute(id, request));
    }
}
