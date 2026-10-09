package ru.itmo.highload.catering.kitchen.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.itmo.highload.catering.kitchen.client.dto.out.OrderResponse;
import ru.itmo.highload.catering.kitchen.service.KitchenService;
import ru.itmo.highload.common.dto.out.PageResponse;
import ru.itmo.highload.common.web.BlockingRequests;
import ru.itmo.highload.common.web.Pagination;

@Hidden
@RestController
@RequestMapping("/internal/v1/kitchen/tasks")
@RequiredArgsConstructor
public class KitchenQueueController {
    private final BlockingRequests blocking;
    private final KitchenService kitchen;

    @GetMapping
    @PreAuthorize("hasRole('KITCHEN_MANAGER')")
    public Mono<PageResponse<OrderResponse>> queue(@RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            Pagination.pageRequest(page, size);
            return kitchen.queue(page, size, "Bearer " + actor.getTokenValue());
        });
    }
}
