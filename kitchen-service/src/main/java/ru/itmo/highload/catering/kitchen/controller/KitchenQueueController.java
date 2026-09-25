package ru.itmo.highload.catering.kitchen.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.common.web.BlockingRequests;
import ru.itmo.highload.common.web.Pagination;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.kitchen.service.KitchenService;

@Hidden
@RestController
@RequestMapping("/internal/v1/kitchen/tasks")
@RequiredArgsConstructor
public class KitchenQueueController {
    private final BlockingRequests blocking;
    private final KitchenService kitchen;

    @GetMapping
    public Mono<PageResponse<OrderResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return blocking.call(() -> {
            Pagination.pageRequest(page, size);
            return kitchen.queue(page, size);
        });
    }
}
