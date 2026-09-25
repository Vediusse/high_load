package ru.itmo.highload.catering.kitchen.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import ru.itmo.highload.common.dto.PageResponse;
import ru.itmo.highload.catering.order.dto.OrderResponse;
import ru.itmo.highload.catering.order.dto.KitchenCommand;

@FeignClient(name = "order-service", url = "${clients.order.url:}")
public interface OrderClient {
    @GetMapping("/internal/v1/orders/{id}")
    OrderResponse get(@PathVariable UUID id, @RequestHeader("X-Trace-Id") String trace);

    @GetMapping("/internal/v1/orders/kitchen")
    PageResponse<OrderResponse> queue(@RequestParam int page, @RequestParam int size,
                                     @RequestHeader("X-Trace-Id") String trace);

    @PostMapping("/internal/v1/orders/{id}/kitchen-commands")
    OrderResponse command(@PathVariable UUID id, @RequestBody KitchenCommand command,
                          @RequestHeader("X-Trace-Id") String trace);
}
