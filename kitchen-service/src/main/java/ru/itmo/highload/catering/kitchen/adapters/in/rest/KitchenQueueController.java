package ru.itmo.highload.catering.kitchen.adapters.in.rest;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import ru.itmo.highload.catering.kitchen.application.KitchenService;
import ru.itmo.highload.catering.kitchen.application.dto.out.OrderResponse;
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
    public Mono<PageResponse<OrderResponse>> queue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt actor) {
        return blocking.call(() -> {
            Pagination.pageRequest(page, size);
            return kitchen.queue(page, size, "Bearer " + actor.getTokenValue());
        });
    }
}
