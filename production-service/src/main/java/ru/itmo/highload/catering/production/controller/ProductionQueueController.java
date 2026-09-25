package ru.itmo.highload.catering.production.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.common.dto.PageResponse;
import ru.itmo.highload.catering.common.web.BlockingRequests;
import ru.itmo.highload.catering.common.web.Pagination;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.production.service.ProductionService;

@Hidden
@RestController
@RequestMapping("/internal/v1/production/tasks")
@RequiredArgsConstructor
public class ProductionQueueController {
    private final BlockingRequests blocking;
    private final ProductionService production;

    @GetMapping
    public Mono<PageResponse<OrderResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        return blocking.call(() -> {
            Pagination.pageRequest(page, size);
            return production.queue(page, size);
        });
    }
}
